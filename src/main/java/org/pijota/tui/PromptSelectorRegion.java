package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

/** Prompt-area region that draws the modal {@link PromptSelector} (the {@code ui.select} picker) when it is active. */
public final class PromptSelectorRegion implements PromptRegion.Custom {

    private final PromptSelector selector;

    PromptSelectorRegion(PromptSelector selector) {
        this.selector = selector;
    }

    @Override
    public Body compose(int width, AnsiTheme theme, PromptSnapshot ctx) {
        return selector.active() ? Body.of(selector.renderLines(theme)) : Body.EMPTY;
    }
}
