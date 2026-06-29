package ai.pijota.tui;

import ai.pijota.llm.ModelDisplay;
import ai.pijota.llm.ModelInfo;

record ModelItem(ModelInfo info, boolean current) implements SelectorItem {
    @Override
    public String searchText() {
        return info.id() + " " + info.provider() + " " + info.displayName();
    }

    @Override
    public String titleText() {
        return info.id();
    }

    @Override
    public String detailText() {
        return (current ? "*  " : "") + "[" + info.provider() + "]  " + ModelDisplay.summary(info);
    }

    @Override
    public boolean selected() {
        return current;
    }
}
