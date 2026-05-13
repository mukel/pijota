package ai.pijota.tui;

import java.util.ArrayList;
import java.util.List;

final class TuiSelectors {
    final Selector<ModelItem> model = new Selector<>(List.of());
    final Selector<SessionItem> session = new Selector<>(List.of());
    final Selector<ThemeItem> theme = new Selector<>(List.of());
    final Selector<SettingsItem> settings = new Selector<>(List.of());
    final Selector<LoginProviderItem> login = new Selector<>(List.of());
    final Selector<ForkMessageItem> fork = new Selector<>(List.of());

    private final List<SelectorBinding> bindings;

    TuiSelectors(Runnable confirmModel, Runnable confirmSession, Runnable confirmTheme,
                 Runnable confirmSettings, Runnable confirmLogin, Runnable confirmFork) {
        bindings = List.of(
            new SelectorBinding(model, confirmModel),
            new SelectorBinding(session, confirmSession),
            new SelectorBinding(theme, confirmTheme),
            new SelectorBinding(settings, confirmSettings),
            new SelectorBinding(login, confirmLogin),
            new SelectorBinding(fork, confirmFork)
        );
    }

    List<PromptRegion> regions() {
        var regions = new ArrayList<PromptRegion>();
        regions.add(new SelectorRegion<>(model));
        regions.add(new SelectorRegion<>(session));
        regions.add(new SelectorRegion<>(theme));
        regions.add(new SelectorRegion<>(settings));
        regions.add(new SelectorRegion<>(login));
        regions.add(new SelectorRegion<>(fork));
        return regions;
    }

    boolean anyActive() {
        for (var binding : bindings) if (binding.selector().active()) return true;
        return false;
    }

    void deactivateAll() {
        for (var binding : bindings) binding.selector().deactivate();
    }

    <T extends SelectorItem> void activate(Selector<T> selector, List<T> items) {
        selector.setItems(items);
        selector.activate(null);
    }

    boolean handle(Key key, Runnable redraw) {
        for (var binding : bindings) {
            if (handle(key, binding.selector(), binding.onConfirm(), redraw)) return true;
        }
        return false;
    }

    void filter(String text) {
        for (var binding : bindings) {
            if (binding.selector().active()) binding.selector().filter(text);
        }
    }

    private static boolean handle(Key key, Selector<?> selector, Runnable onConfirm, Runnable redraw) {
        if (!selector.active()) return false;
        if (key == Key.Special.UP)          { selector.selectPrev(); redraw.run(); return true; }
        if (key == Key.Special.DOWN)        { selector.selectNext(); redraw.run(); return true; }
        if (key == Key.Special.PG_UP)       { selector.selectPageUp(); redraw.run(); return true; }
        if (key == Key.Special.PG_DOWN)     { selector.selectPageDown(); redraw.run(); return true; }
        if (key == Key.Special.ENTER)       { onConfirm.run(); redraw.run(); return true; }
        return false;
    }

    private record SelectorBinding(Selector<?> selector, Runnable onConfirm) {}
}
