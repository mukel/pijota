package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import java.io.PrintWriter;

/** Prompt-area region that draws the modal {@link PromptSelector} (the {@code ui.select} picker) when it is active. */
public final class PromptSelectorRegion implements PromptRegion.Custom {

    private final PromptSelector selector;

    PromptSelectorRegion(PromptSelector selector) {
        this.selector = selector;
    }

    @Override
    public int height(int width, PromptSnapshot ctx) {
        return selector.active() ? selector.lineCount() : 0;
    }

    @Override
    public CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx) {
        if (!selector.active())
            return NO_CURSOR;
        PromptRegion.writeLines(out, selector.renderLines(theme));
        return NO_CURSOR;
    }
}
