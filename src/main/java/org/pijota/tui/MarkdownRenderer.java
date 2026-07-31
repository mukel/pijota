package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import java.util.List;

/**
 * Renders a committed markdown chunk (one line, or a buffered table) to styled terminal lines. Two implementations:
 * {@link TerminalMarkdownRenderer} (barebones, keeps delimiters dimmed) and {@link RichMarkdownRenderer} (consumes
 * delimiters and styles the text). Fenced code blocks are handled upstream in {@link StreamingMarkdownFormatter}.
 */
interface MarkdownRenderer {
    List<StyledLine> render(String markdown, String defaultTextSlot, boolean defaultItalic);

    void setTheme(AnsiTheme theme);

    void setWidth(int width);
}
