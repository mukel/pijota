package org.pijota.tui;

import org.pijota.llm.ModelDisplay;
import org.pijota.llm.ModelInfo;

record ModelItem(ModelInfo info, boolean current) implements SelectorItem {
    @Override
    public String searchText() {
        return info.id() + " " + info.provider() + " " + info.displayName();
    }

    @Override
    public String titleText() {
        return info.id();
    }

    /**
     * The provider is its own chip, not glued to the summary with spaces. A narrow row drops whole chips, and while
     * {@code "[deepseek]  context 976K"} was one chip it could only be ellipsised as a unit -- at 60 columns every row
     * read {@code [deepseek]  cont…}. Separately, the provider survives alone.
     */
    @Override
    public String detailText() {
        return "[" + info.provider() + "] · " + ModelDisplay.summary(info);
    }

    @Override
    public boolean selected() {
        return current;
    }
}
