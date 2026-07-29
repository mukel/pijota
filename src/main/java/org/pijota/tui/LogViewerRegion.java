package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

/** Prompt-area region that draws the modal {@link LogViewer} when it is open. */
public final class LogViewerRegion implements PromptRegion.Custom {

    private final LogViewer viewer;

    LogViewerRegion(LogViewer viewer) {
        this.viewer = viewer;
    }

    @Override
    public Body compose(int width, AnsiTheme theme, PromptSnapshot ctx) {
        return viewer.active() ? Body.of(viewer.renderLines(theme, width)) : Body.EMPTY;
    }
}
