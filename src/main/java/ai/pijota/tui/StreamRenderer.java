package ai.pijota.tui;

import ai.pijota.theme.AnsiTheme;
import ai.pijota.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static ai.pijota.util.Util.deltaPrefix;

/**
 * Renders streaming assistant content and "thinking" into terminal lines, emitting finished lines to an {@link Output}
 * sink. Owns all streaming text state — delta tracking, markdown formatters, line accumulators, and the thinking
 * header/footer lifecycle; the host TUI only forwards stream updates and lifecycle calls.
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
    private AnsiTheme thinkingTheme;
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
                                    BooleanSupplier showThinking, Supplier<String> thinkingLabel, MarkdownStyle style) {
        this.output = output;
        this.theme = theme;
        this.thinkingTheme = thinkingTheme;
        this.usableWidth = usableWidth;
        this.showThinking = showThinking;
        this.thinkingLabel = thinkingLabel;
        this.markdown = new StreamingMarkdownFormatter(theme, style);
        // Thinking renders through the SAME markdown pipeline/config as content (default body slot, no forced italic);
        // the only difference is the fully-dimmed thinkingTheme, so syntax highlighting, tables, bold/italic etc. all
        // appear exactly like regular output, just dimmed.
        this.thinkingMarkdown = new StreamingMarkdownFormatter(thinkingTheme, style);
    }

    void setTheme(AnsiTheme theme, AnsiTheme thinkingTheme) {
        this.theme = theme;
        this.thinkingTheme = thinkingTheme;
        this.markdown.setTheme(theme);
        this.thinkingMarkdown.setTheme(thinkingTheme);
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

    /**
     * Streams a thinking update (caller decides whether thinking should be shown). Returns true if it changed
     * scrollback this call (emitted the header or committed a completed line), signalling the caller to do a full
     * redraw; otherwise the live thinking tail is repainted in place.
     */
    boolean streamThinking(String fullThinking) {
        boolean changed = false;
        if (!thinkingHeaderShown) {
            thinkingStartedAt = System.currentTimeMillis();
            // Blank line before the header (not after the summary): separates the block from whatever preceded it
            // while keeping the closing summary flush against the answer that follows.
            emitPlain(List.of("", thinkingHeader()));
            thinkingHeaderShown = true;
            changed = true;
        }
        String delta = deltaPrefix(fullThinking, lastThinking);
        var committed = thinkingLines.accept(delta);
        appendThinking(committed);
        lastThinking = fullThinking;
        return changed || !committed.isEmpty();
    }

    /**
     * Streams a content update, closing the thinking block on the first non-empty content. Returns true if it committed
     * one or more completed lines this call (a newline arrived), signalling the caller to do a full redraw so the
     * committed line and the refreshed tail land in the same frame.
     */
    boolean streamContent(String fullContent) {
        String delta = deltaPrefix(fullContent, lastContent);
        if (!delta.isEmpty() && !contentStarted) {
            if (thinkingHeaderShown)
                closeThinking();
            contentStarted = true;
        }
        var committed = contentLines.accept(delta);
        appendContentLines(committed);
        lastContent = fullContent;
        return !committed.isEmpty();
    }

    /**
     * The in-progress content line for live preview at the bottom of the screen, or empty when there is nothing to
     * preview. Non-consuming: the partial is still committed (markdown-formatted, filtered) on its terminating newline
     * via {@link #streamContent}/{@link #flush}. The preview is deliberately plain — it skips the stateful
     * markdown/system-reminder pipeline, so a line may visually reflow when it commits. Suppressed inside a
     * {@code <system-reminder>} span or for reasoning-meta lines (both would be stripped on commit anyway).
     */
    Optional<String> contentLiveTail() {
        if (!contentStarted || streamProc.suppressing())
            return Optional.empty();
        String partial = contentLines.partialView();
        int reminder = partial.indexOf("<system-reminder>");
        if (reminder >= 0)
            partial = partial.substring(0, reminder);
        if (partial.isBlank() || StreamProcessor.isReasoningMetaLine(partial.strip()))
            return Optional.empty();
        return Optional.of(partial);
    }

    /**
     * The in-progress line to preview live at the bottom of the screen, PRE-STYLED: the thinking partial (dimmed, via
     * the thinking theme) while reasoning, then the content partial (normal). Raw — no markdown — so it appears
     * instantly and may reflow into formatted/dimmed scrollback when the line commits. Empty when nothing is streaming.
     */
    Optional<String> liveTail() {
        if (contentStarted)
            return contentLiveTail().map(t -> theme.styled("text", t));
        if (thinkingHeaderShown && !thinkingFooterShown) {
            String p = thinkingLines.partialView();
            if (!p.isBlank() && !StreamProcessor.isReasoningMetaLine(p.strip()))
                return Optional.of(thinkingTheme.styled("text", p));
        }
        return Optional.empty();
    }

    void closeThinking() {
        if (!thinkingHeaderShown || thinkingFooterShown)
            return;
        thinkingLines.flush().ifPresent(line -> appendThinking(List.of(line)));
        // Flush the thinking markdown buffer now so the thinking body is emitted IN ORDER (before the summary and the
        // content). Otherwise it stays buffered until the global flush(), which emits content first — pushing the
        // whole thinking block below the answer where it looks like it never rendered.
        var tail = thinkingMarkdown.flush();
        if (!tail.isEmpty())
            output.appendLines(tail);
        emitPlain(List.of(thinkingSummary(lastThinking, thinkingStartedAt, theme)));
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
