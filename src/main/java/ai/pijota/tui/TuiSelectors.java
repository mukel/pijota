package ai.pijota.tui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

final class TuiSelectors {
    enum Slot {
        MODEL(ModelItem.class, "models"), SESSION(SessionItem.class, "sessions"), THEME(ThemeItem.class,
                                        "themes"), SETTINGS(SettingsItem.class, "settings"), LOGIN(
                                                                        LoginProviderItem.class,
                                                                        "providers"), FORK(ForkMessageItem.class,
                                                                                                        "fork from"), PALETTE(CompletionItem.class,
                                                                                                                                        "command palette");

        private final Class<? extends SelectorItem> itemType;
        private final String title;

        Slot(Class<? extends SelectorItem> itemType, String title) {
            this.itemType = itemType;
            this.title = title;
        }
    }

    private final EnumMap<Slot, SelectorBinding> bindings = new EnumMap<>(Slot.class);

    TuiSelectors(Runnable confirmModel, Runnable confirmSession, Runnable confirmTheme, Runnable confirmSettings,
                                    Runnable confirmLogin, Runnable confirmFork, Runnable confirmPalette) {
        bind(Slot.MODEL, confirmModel);
        bind(Slot.SESSION, confirmSession);
        bind(Slot.THEME, confirmTheme);
        bind(Slot.SETTINGS, confirmSettings);
        bind(Slot.LOGIN, confirmLogin);
        bind(Slot.FORK, confirmFork);
        bind(Slot.PALETTE, confirmPalette);
    }

    List<PromptRegion> regions() {
        var regions = new ArrayList<PromptRegion>();
        for (var binding : bindings.values())
            regions.add(new SelectorRegion<>(binding.selector()));
        return regions;
    }

    boolean anyActive() {
        for (var binding : bindings.values())
            if (binding.selector().active())
                return true;
        return false;
    }

    void deactivateAll() {
        for (var binding : bindings.values())
            binding.selector().deactivate();
    }

    <T extends SelectorItem> void show(Slot slot, List<T> items) {
        show(slot, items, null);
    }

    <T extends SelectorItem> void show(Slot slot, List<T> items, String query) {
        checkItems(slot, items);
        selector(slot).setTitle(slot.title);
        selector(slot).setItems(new ArrayList<>(items));
        selector(slot).activate(query);
    }

    <T extends SelectorItem> void selectFirst(Slot slot, Predicate<T> test) {
        selector(slot).selectFirst(item -> test.test(cast(slot, item)));
    }

    <T extends SelectorItem> Optional<T> selected(Slot slot) {
        var selector = selector(slot);
        if (!selector.active() || selector.total() == 0)
            return Optional.empty();
        return Optional.of(cast(slot, selector.selectedItem()));
    }

    void deactivate(Slot slot) {
        selector(slot).deactivate();
    }

    <T extends SelectorItem> void refresh(Slot slot, List<T> items) {
        checkItems(slot, items);
        selector(slot).setItems(new ArrayList<>(items));
    }

    boolean handle(Key key, Runnable redraw) {
        for (var binding : bindings.values()) {
            if (handle(key, binding.selector(), binding.onConfirm(), redraw))
                return true;
        }
        return false;
    }

    void filter(String text) {
        for (var binding : bindings.values()) {
            if (binding.selector().active())
                binding.selector().filter(text);
        }
    }

    private Selector<SelectorItem> selector(Slot slot) {
        return bindings.get(slot).selector();
    }

    private static void checkItems(Slot slot, List<? extends SelectorItem> items) {
        for (var item : items) {
            if (!slot.itemType.isInstance(item)) {
                throw new IllegalArgumentException(slot + " selector cannot show " + item.getClass().getSimpleName());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends SelectorItem> T cast(Slot slot, SelectorItem item) {
        return (T) slot.itemType.cast(item);
    }

    private void bind(Slot slot, Runnable onConfirm) {
        bindings.put(slot, new SelectorBinding(new Selector<>(List.of()), onConfirm));
    }

    private static boolean handle(Key key, Selector<?> selector, Runnable onConfirm, Runnable redraw) {
        if (!selector.active())
            return false;
        if (key == Key.Special.UP) {
            selector.selectPrev();
            redraw.run();
            return true;
        }
        if (key == Key.Special.DOWN) {
            selector.selectNext();
            redraw.run();
            return true;
        }
        if (key == Key.Special.PG_UP) {
            selector.selectPageUp();
            redraw.run();
            return true;
        }
        if (key == Key.Special.PG_DOWN) {
            selector.selectPageDown();
            redraw.run();
            return true;
        }
        if (key == Key.Special.ENTER) {
            onConfirm.run();
            redraw.run();
            return true;
        }
        return false;
    }

    private record SelectorBinding(Selector<SelectorItem> selector, Runnable onConfirm) {
    }
}
