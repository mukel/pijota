package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;
import ai.pijota.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static ai.pijota.util.Util.deltaPrefix;

/**
 * Renders streaming assistant content and "thinking" into terminal lines. Owns all streaming text state — delta
 * tracking, markdown formatters, line accumulators, and the thinking header/footer lifecycle — and emits finished lines
 * to an {@link Output} sink. Self-contained and unit-testable with a capturing sink; the host TUI only forwards stream
 * updates and lifecycle calls and keeps owning everything else (tool results, prompt redraws, usage).
 */
final class StreamRenderer {

    /** Sink for finished, styled lines — in the TUI this is the append-only scrollback. */
    interface Output {
        void appendLines(List<StyledLine> lines);
    }

    private final Output output;
    private final IntSupplier usableWidth;
    private final BooleanSupplier showThinking;
    private final Supplier<String> thinkingLabel;

    private AnsiTheme theme;
    private final StreamingMarkdownFormatter markdown;
    private final StreamingMarkdownFormatter thinkingMarkdown;
    private final LineAccumulator contentLines = new LineAccumulator();
    private final LineAccumulator thinkingLines = new LineAccumulator();
    private final StreamProcessor streamProc = new StreamProcessor();

    private boolean thinkingHeaderShown;
    private boolean thinkingFooterShown;
    private long thinkingStartedAt;
    private String lastContent = "";
    private String lastThinking = "";
    private boolean compactionStreamRendered;
    private boolean contentStarted;

    StreamRenderer(Output output, AnsiTheme theme, AnsiTheme thinkingTheme, IntSupplier usableWidth,
                                    BooleanSupplier showThinking, Supplier<String> thinkingLabel) {
        this.output = output;
        this.theme = theme;
        this.usableWidth = usableWidth;
        this.showThinking = showThinking;
        this.thinkingLabel = thinkingLabel;
        this.markdown = new StreamingMarkdownFormatter(theme);
        this.thinkingMarkdown = new StreamingMarkdownFormatter(thinkingTheme, "thinkingText", true);
    }

    void setTheme(AnsiTheme theme, AnsiTheme thinkingTheme) {
        this.theme = theme;
        this.markdown.setTheme(theme);
        this.thinkingMarkdown.setTheme(thinkingTheme);
    }

    boolean thinkingHeaderShown() {
        return thinkingHeaderShown;
    }

    boolean compactionStreamRendered() {
        return compactionStreamRendered;
    }

    void clearCompactionStream() {
        compactionStreamRendered = false;
    }

    void reset() {
        contentLines.clear();
        thinkingLines.clear();
        streamProc.reset();
        contentStarted = false;
        thinkingHeaderShown = false;
        thinkingFooterShown = false;
        thinkingStartedAt = 0;
        lastContent = "";
        lastThinking = "";
        markdown.reset();
        thinkingMarkdown.reset();
    }

    /** Streams a thinking update. The caller decides whether thinking should be shown before calling this. */
    void streamThinking(String fullThinking) {
        if (!thinkingHeaderShown) {
            thinkingStartedAt = System.currentTimeMillis();
            emitPlain(List.of(thinkingHeader()));
            thinkingHeaderShown = true;
        }
        String delta = deltaPrefix(fullThinking, lastThinking);
        appendThinking(thinkingLines.accept(delta));
        lastThinking = fullThinking;
    }

    /** Streams a content update, closing the thinking block on the first non-empty content. */
    void streamContent(String fullContent) {
        String delta = deltaPrefix(fullContent, lastContent);
        if (!delta.isEmpty() && !contentStarted) {
            if (thinkingHeaderShown)
                closeThinking();
            contentStarted = true;
        }
        appendContentLines(contentLines.accept(delta));
        lastContent = fullContent;
    }

    /** Appends a whole block of content at once (used for already-complete, non-streamed messages). */
    void appendContent(String text) {
        appendContentLines(contentLines.accept(text));
    }

    void closeThinking() {
        if (!thinkingHeaderShown || thinkingFooterShown)
            return;
        thinkingLines.flush().ifPresent(line -> appendThinking(List.of(line)));
        emitPlain(List.of(thinkingSummary(lastThinking, thinkingStartedAt, theme), ""));
        thinkingFooterShown = true;
    }

    void flush() {
        int width = usableWidth.getAsInt();
        List<List<StyledLine>> batches = new ArrayList<>();
        synchronized (this) {
            if (showThinking.getAsBoolean())
                thinkingLines.flush().ifPresent(line -> batches.add(renderThinking(List.of(line))));
            contentLines.flush().ifPresent(line -> batches.add(renderContent(List.of(line), width)));
            markdown.setWidth(width);
            batches.add(markdown.flush());
            thinkingMarkdown.setWidth(width);
            batches.add(thinkingMarkdown.flush());
        }
        for (var batch : batches)
            output.appendLines(batch);
    }

    void renderCompactionStream(String content) {
        String text = content == null ? "" : Util.stripSystemReminders(content);
        String delta = deltaPrefix(text, lastContent);
        if (!delta.isEmpty()) {
            if (!compactionStreamRendered)
                emitPlain(List.of(theme.styled("statusText", "  ── compacting ──")));
            appendContentLines(contentLines.accept(delta));
            lastContent = text;
            compactionStreamRendered = true;
        }
    }

    void renderCompactionSummary(String summary) {
        reset();
        emitPlain(List.of(theme.styled("statusText", "  ── compacted ──")));
        appendContentLines(contentLines.accept(summary));
        flush();
    }

    static String thinkingSummary(String thinking, long startedAt, AnsiTheme theme) {
        int tokens = (int) Math.ceil((thinking == null ? 0 : thinking.length()) / 4.0);
        long elapsed = startedAt > 0 ? System.currentTimeMillis() - startedAt : 0;
        return theme.styled("thinkingText", "└ ") + theme.styled("thinkingText", "thought ")
                                        + theme.styled("thinkingText", Util.fmtCount(tokens) + " tokens")
                                        + theme.styled("thinkingText", " · " + Util.formatDuration(elapsed));
    }

    // ---- internals ----

    private void appendContentLines(List<String> lines) {
        if (lines.isEmpty())
            return;
        int width = usableWidth.getAsInt();
        List<StyledLine> rendered;
        synchronized (this) {
            rendered = renderContent(lines, width);
        }
        output.appendLines(rendered);
    }

    private void appendThinking(List<String> lines) {
        if (lines.isEmpty())
            return;
        List<StyledLine> rendered;
        synchronized (this) {
            rendered = renderThinking(lines);
        }
        output.appendLines(rendered);
    }

    private List<StyledLine> renderContent(List<String> lines, int width) {
        markdown.setWidth(width);
        var out = new ArrayList<StyledLine>();
        for (String line : lines) {
            String cleaned = streamProc.stripAndFilter(line);
            if (cleaned != null)
                out.addAll(markdown.format(cleaned));
        }
        return out;
    }

    private List<StyledLine> renderThinking(List<String> lines) {
        int width = usableWidth.getAsInt();
        thinkingMarkdown.setWidth(width);
        var out = new ArrayList<StyledLine>();
        for (String line : lines)
            out.addAll(thinkingMarkdown.format(line));
        return out;
    }

    private void emitPlain(List<String> lines) {
        output.appendLines(lines.stream().map(StyledLine::of).toList());
    }

    private String thinkingHeader() {
        return theme.styled("toolLabel", "› thinking") + theme.styled("dim", " · " + thinkingLabel.get());
    }
}
