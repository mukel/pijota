package ai.pijota.tui;

import ai.pijota.modes.SyntaxHighlighter;
import ai.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class StreamingMarkdownFormatter {
    private final MarkdownStreamBuffer buffer = new MarkdownStreamBuffer();
    private final TerminalMarkdownRenderer renderer;
    private AnsiTheme theme;
    private boolean inFence;
    private SyntaxHighlighter highlighter;

    public StreamingMarkdownFormatter(AnsiTheme theme) {
        this(theme, null);
    }

    public StreamingMarkdownFormatter(AnsiTheme theme, String plainSlot) {
        this.theme = theme;
        this.renderer = new TerminalMarkdownRenderer(theme, plainSlot);
    }

    public void setWidth(int width) {
        renderer.setWidth(width);
    }

    public void setTheme(AnsiTheme theme) {
        this.theme = theme;
        renderer.setTheme(theme);
        if (inFence && highlighter != null) highlighter = new SyntaxHighlighter(highlighter.language(), theme);
    }

    public List<StyledLine> format(String raw) {
        String line = raw == null ? "" : raw;
        String stripped = line.stripLeading();
        String marker = fenceMarker(stripped);
        if (marker != null) return toggleFence(stripped, marker);
        if (inFence) return renderer.renderCodeLine(line, highlighter);
        return renderChunks(buffer.accept(line));
    }

    public List<StyledLine> flush() {
        var out = renderChunks(buffer.flush());
        if (inFence) {
            leaveFence();
            out.add(renderer.renderFenceClose());
        }
        return out;
    }

    public void reset() {
        buffer.clear();
        leaveFence();
    }

    private List<StyledLine> toggleFence(String stripped, String marker) {
        var out = new ArrayList<>(renderChunks(buffer.flush()));
        if (inFence) {
            leaveFence();
            out.add(renderer.renderFenceClose());
        } else {
            String info = enterFence(stripped, marker);
            out.add(renderer.renderFenceOpen(info));
        }
        return out;
    }

    private String enterFence(String stripped, String marker) {
        String info = stripped.substring(marker.length()).strip();
        inFence = true;
        highlighter = isVerbatimFence(info) ? null : new SyntaxHighlighter(info, theme);
        return info;
    }

    private void leaveFence() {
        inFence = false;
        highlighter = null;
    }

    private List<StyledLine> renderChunks(List<MarkdownChunk> chunks) {
        var out = new ArrayList<StyledLine>();
        for (var chunk : chunks) {
            if (chunk.blankLine()) out.add(StyledLine.of(""));
            else out.addAll(renderer.render(chunk.markdown()));
        }
        return out;
    }

    private static String fenceMarker(String stripped) {
        if (stripped.startsWith("```")) return "```";
        if (stripped.startsWith("~~~")) return "~~~";
        return null;
    }

    private static boolean isVerbatimFence(String info) {
        if (info == null || info.isBlank()) return false;
        String lang = info.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return lang.equals("markdown") || lang.equals("md") || lang.equals("gfm") || lang.equals("commonmark");
    }
}
