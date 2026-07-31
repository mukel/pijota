package org.pijota.tui;

import org.pijota.theme.AnsiTheme;
import org.pijota.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static org.pijota.util.Util.deltaPrefix;

/**
 * Renders streaming assistant content and "thinking" into terminal lines, emitting finished lines to an {@link Output}
 * sink. Owns all streaming text state: delta tracking, markdown formatters, line accumulators, and the thinking
 * header/footer lifecycle; the host TUI only forwards stream updates and lifecycle calls.
 */
final class StreamRenderer {

    /** Sink for finished, styled lines. In the TUI this is the append-only scrollback. */
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
    private boolean blankBeforeNextContent;

    StreamRenderer(Output output, AnsiTheme theme, AnsiTheme thinkingTheme, IntSupplier usableWidth,
                                    BooleanSupplier showThinking, Supplier<String> thinkingLabel, MarkdownStyle style) {
        this.output = output;
        this.theme = theme;
        this.thinkingTheme = thinkingTheme;
        this.usableWidth = usableWidth;
        this.showThinking = showThinking;
        this.thinkingLabel = thinkingLabel;
        this.markdown = new StreamingMarkdownFormatter(theme, style);
        // Thinking renders through the SAME markdown pipeline/config as content, but through the thinkingTheme:
        // prose collapses to the one dim thinking colour (an inline-code span never out-shouts the muted body),
        // while fenced code keeps its syntax hues blended toward the background, dimmed, still readable as code.
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
        blankBeforeNextContent = false;
        markdown.reset();
        thinkingMarkdown.reset();
    }

    /**
     * The answer gets one blank line of air after a machine block (tool result). Called by the TUI at that boundary;
     * the next committed content batch is preceded by a single blank line (the coordinator collapses doubles).
     */
    void separateNextContent() {
        blankBeforeNextContent = true;
    }

    /**
     * Records thinking that is NOT displayed (thinking display off) so {@link #closeThinking} can still explain the
     * pause with a one-line summary. Tracks the same start time / last text as the visible path, emits nothing.
     */
    void recordThinking(String fullThinking) {
        if (thinkingStartedAt == 0)
            thinkingStartedAt = System.currentTimeMillis();
        lastThinking = fullThinking;
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
            closeThinking(); // idempotent; also emits the ∴ stand-in when thinking was recorded but hidden
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
     * via {@link #streamContent}/{@link #flush}. The preview is deliberately plain. It skips the stateful
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
     * the thinking theme) while reasoning, then the content partial (normal). Raw, no markdown, so it appears
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
        if (thinkingFooterShown)
            return;
        if (!thinkingHeaderShown) {
            // Hidden thinking (display off): one dim line so a long pause is always explained.
            if (lastThinking.isBlank())
                return;
            emitPlain(List.of("", collapsedThinkingSummary(lastThinking, thinkingStartedAt, theme), ""));
            thinkingFooterShown = true;
            return;
        }
        thinkingLines.flush().ifPresent(line -> appendThinking(List.of(line)));
        // Flush the thinking markdown buffer now so the thinking body is emitted IN ORDER (before the summary and the
        // content). Otherwise it stays buffered until the global flush(), which emits content first, pushing the
        // whole thinking block below the answer where it looks like it never rendered.
        thinkingMarkdown.setWidth(railedWidth());
        var tail = thinkingMarkdown.flush();
        if (!tail.isEmpty())
            output.appendLines(railed(tail));
        // Trailing blank line so the summary is visually separated from the answer that follows.
        emitPlain(List.of(thinkingSummary(lastThinking, thinkingStartedAt, theme), ""));
        thinkingFooterShown = true;
    }

    void flush() {
        int width = usableWidth.getAsInt();
        List<List<StyledLine>> batches = new ArrayList<>();
        synchronized (this) {
            if (showThinking.getAsBoolean())
                thinkingLines.flush().ifPresent(line -> batches.add(renderThinking(List.of(line))));
            contentLines.flush().ifPresent(line -> {
                var rendered = renderContent(List.of(line), width);
                if (!rendered.isEmpty() && blankBeforeNextContent) {
                    blankBeforeNextContent = false;
                    batches.add(List.of(StyledLine.of("")));
                }
                batches.add(rendered);
            });
            markdown.setWidth(width);
            batches.add(markdown.flush());
            thinkingMarkdown.setWidth(railedWidth());
            batches.add(railed(thinkingMarkdown.flush()));
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

    /** Footer of a visible thinking block. The header already said "thinking", so only the numbers remain. */
    static String thinkingSummary(String thinking, long startedAt, AnsiTheme theme) {
        return theme.styled("thinkingText", "└ " + thinkingMeta(thinking, startedAt));
    }

    /** The one-line stand-in when thinking display is off: the pause is explained, nothing else is shown. */
    static String collapsedThinkingSummary(String thinking, long startedAt, AnsiTheme theme) {
        return theme.styled("thinkingText", "∴ thought " + thinkingMeta(thinking, startedAt));
    }

    private static String thinkingMeta(String thinking, long startedAt) {
        int tokens = (int) Util.approxTokens(thinking == null ? 0 : thinking.length());
        long elapsed = startedAt > 0 ? System.currentTimeMillis() - startedAt : 0;
        String meta = Util.count(tokens, "token", "tokens");
        if (elapsed >= 1000) // a sub-second pause needs no clock. "· 0s" is noise
            meta += " · " + Util.formatDuration(elapsed);
        return meta;
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
        if (rendered.isEmpty())
            return;
        if (blankBeforeNextContent) {
            blankBeforeNextContent = false;
            output.appendLines(List.of(StyledLine.of("")));
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
        thinkingMarkdown.setWidth(railedWidth());
        var out = new ArrayList<StyledLine>();
        for (String line : lines)
            out.addAll(thinkingMarkdown.format(line));
        return railed(out);
    }

    /** Columns left for thinking content once the rail has claimed its two. */
    private int railedWidth() {
        return Math.max(1, usableWidth.getAsInt() - 2);
    }

    /**
     * The single choke point that puts the {@code │} rail on thinking output: every logical line is wrapped to the
     * railed width FIRST, then every physical row, continuations included, gets the rail. A long thought can never
     * shed its rail at the terminal edge, and no emission path (streaming, close, flush) can bypass it.
     */
    private List<StyledLine> railed(List<StyledLine> lines) {
        int width = railedWidth();
        String rail = theme.styled("thinkingText", "│ "); // corners, rail and footer all speak with one thinking voice
        var out = new ArrayList<StyledLine>();
        for (var line : lines)
            for (String row : AnsiWidth.wrapPreservingAnsi(line.text(), width))
                out.add(StyledLine.of(rail + row));
        return out;
    }

    private void emitPlain(List<String> lines) {
        output.appendLines(lines.stream().map(StyledLine::of).toList());
    }

    private String thinkingHeader() {
        // The level label appears only when it deviates from the default. "┌ thinking" alone is calmer. The header
        // wears the thinking slot, not the tool accent: thinking is the quietest voice on screen, and an accent
        // header would make its blocks the loudest thing in the transcript.
        String label = thinkingLabel.get();
        String suffix = label == null || label.isBlank() || "medium".equals(label) ? "" : " · " + label;
        return theme.styled("thinkingText", "┌ thinking" + suffix);
    }
}
