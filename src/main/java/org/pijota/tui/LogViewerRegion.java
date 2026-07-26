package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import java.io.PrintWriter;

/** Prompt-area region that draws the modal {@link LogViewer} when it is open. */
public final class LogViewerRegion implements PromptRegion.Custom {

    private final LogViewer viewer;

    LogViewerRegion(LogViewer viewer) {
        this.viewer = viewer;
    }

    @Override
    public int height(int width, PromptSnapshot ctx) {
        return viewer.active() ? viewer.lineCount() : 0;
    }

    @Override
    public CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx) {
        if (!viewer.active())
            return NO_CURSOR;
        PromptRegion.writeLines(out, viewer.renderLines(theme, width));
        return NO_CURSOR;
    }
}
