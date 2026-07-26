package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

final class TerminalFrameRenderer {
    static final String HIDE_CURSOR = "\033[?25l";
    static final String SHOW_CURSOR = "\033[?25h";

    // CSI 2026 / DECSET Synchronized Output — tells the terminal to buffer all
    // writes between BEGIN and END, then apply them as one atomic frame.
    static final String BEGIN_SYNC = "\033[?2026h";
    static final String END_SYNC = "\033[?2026l";

    private final PromptLayout layout;

    TerminalFrameRenderer(PromptLayout layout) {
        this.layout = layout;
    }

    String renderFrame(int width, int height, PromptSnapshot snapshot, List<StyledLine> lines, boolean erase,
                                    boolean showPrompt, AnsiTheme theme) {
        var buf = new StringWriter();
        var out = new PrintWriter(buf);
        out.print(BEGIN_SYNC);
        out.print(HIDE_CURSOR);
        if (erase)
            layout.eraseCurrent(out);
        // Append committed lines write-then-trim, wrapping them to physical rows ourselves so no terminal auto-wrap can
        // leave stale content a per-line clear would miss. No frame blanks the region up front, so nothing strobes.
        for (var line : lines) {
            for (var physical : AnsiWidth.wrapPreservingAnsi(line.text(), width)) {
                out.print('\r');
                out.print(physical);
                out.print("\033[K\r\n");
            }
        }
        if (showPrompt && snapshot != null) {
            layout.renderAfterErase(out, width, height, theme, snapshot);
        }
        out.print(SHOW_CURSOR);
        out.print(END_SYNC);
        out.flush();
        return buf.toString();
    }
}
