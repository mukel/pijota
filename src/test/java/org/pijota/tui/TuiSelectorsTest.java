package org.pijota.tui;

import org.pijota.llm.ModelInfo;
import org.pijota.messages.Message;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TuiSelectorsTest {

    @Test
    void showAndSelectFirst() {
        var selectors = new TuiSelectors();
        var items = List.<SelectorItem>of(new ModelItem(new ModelInfo("a", "p", "A", 1000), false),
                                        new ModelItem(new ModelInfo("b", "p", "B", 2000), true));
        selectors.show(TuiSelectors.Slot.MODEL, items);

        var selected = selectors.selected(TuiSelectors.Slot.MODEL);
        assertTrue(selected.isPresent());

        selectors.selectFirst(TuiSelectors.Slot.MODEL, item -> item.searchText().contains("b"));
        selected = selectors.selected(TuiSelectors.Slot.MODEL);
        assertTrue(selected.get().searchText().contains("b"));
    }

    @Test
    void checkItemsRejectsWrongType() {
        var selectors = new TuiSelectors();
        var items = List.<SelectorItem>of(new SettingsItem("id", "t", "d", false));
        assertThrows(IllegalArgumentException.class, () -> {
            selectors.show(TuiSelectors.Slot.MODEL, items);
        });
    }

    @Test
    void checkItemsAcceptsCorrectType() {
        var selectors = new TuiSelectors();
        var items = List.<SelectorItem>of(new SettingsItem("id", "t", "d", false));
        assertDoesNotThrow(() -> selectors.show(TuiSelectors.Slot.SETTINGS, items));
    }

    @Test
    void deactivateClearsSelection() {
        var selectors = new TuiSelectors();
        var items = List.<SelectorItem>of(new ThemeItem("dark", "", false));
        selectors.show(TuiSelectors.Slot.THEME, items);
        assertTrue(selectors.anyActive());

        selectors.deactivate(TuiSelectors.Slot.THEME);
        assertFalse(selectors.anyActive());
    }

    @Test
    void deactivateAllClearsEverything() {
        var selectors = new TuiSelectors();
        var items = List.<SelectorItem>of(new ThemeItem("dark", "", false));
        selectors.show(TuiSelectors.Slot.THEME, items);
        selectors.show(TuiSelectors.Slot.SETTINGS, List.of(new SettingsItem("x", "t", "d", false)));
        assertTrue(selectors.anyActive());

        selectors.deactivateAll();
        assertFalse(selectors.anyActive());
    }

    @Test
    void selectedReturnsEmptyWhenInactive() {
        var selectors = new TuiSelectors();
        assertTrue(selectors.selected(TuiSelectors.Slot.MODEL).isEmpty());
    }

    @Test
    void regionsReturnsNonEmpty() {
        var selectors = new TuiSelectors();
        var regions = selectors.regions();
        assertFalse(regions.isEmpty());
    }

    @Test
    void handleUpDownTriggersRedraw() {
        var selectors = new TuiSelectors();
        var items = List.<SelectorItem>of(new SettingsItem("a", "t", "d", false),
                                        new SettingsItem("b", "t", "d", false));
        selectors.show(TuiSelectors.Slot.SETTINGS, items);

        var redrawCount = new int[1];
        assertTrue(selectors.handle(Key.Special.UP, () -> redrawCount[0]++));
        assertEquals(1, redrawCount[0]);

        assertTrue(selectors.handle(Key.Special.DOWN, () -> redrawCount[0]++));
        assertEquals(2, redrawCount[0]);
    }

    @Test
    void handlePgUpPgDown() {
        var selectors = new TuiSelectors();
        var items = List.<SelectorItem>of(new SettingsItem("a", "", "", false));
        selectors.show(TuiSelectors.Slot.SETTINGS, items);

        var redrawCount = new int[1];
        assertTrue(selectors.handle(Key.Special.PG_UP, () -> redrawCount[0]++));
        assertTrue(selectors.handle(Key.Special.PG_DOWN, () -> redrawCount[0]++));
    }

    @Test
    void handleEnterCallsConfirmAndRedraw() {
        var confirmCount = new int[1];
        var redrawCount = new int[1];
        var selectors = new TuiSelectors();
        selectors.bind(TuiSelectors.Slot.MODEL, () -> confirmCount[0]++);
        var items = List.<SelectorItem>of(new ModelItem(new ModelInfo("m", "p", "M", 1000), false));
        selectors.show(TuiSelectors.Slot.MODEL, items);

        assertTrue(selectors.handle(Key.Special.ENTER, () -> redrawCount[0]++));
        assertEquals(1, confirmCount[0]);
        assertEquals(1, redrawCount[0]);
    }

    @Test
    void handleReturnsFalseWhenInactive() {
        var selectors = new TuiSelectors();
        assertFalse(selectors.handle(Key.Special.UP, () -> {}));
    }

    @Test
    void refreshUpdatesItems() {
        var selectors = new TuiSelectors();
        var items1 = List.<SelectorItem>of(new SettingsItem("old", "oldT", "oldD", false));
        selectors.show(TuiSelectors.Slot.SETTINGS, items1);

        var items2 = List.<SelectorItem>of(new SettingsItem("new", "newT", "newD", false));
        selectors.refresh(TuiSelectors.Slot.SETTINGS, items2);

        var selected = selectors.selected(TuiSelectors.Slot.SETTINGS);
        assertTrue(selected.isPresent());
        assertTrue(selected.get().searchText().contains("new"));
    }

    @Test
    void slotEnumHasAllSlots() {
        assertNotNull(TuiSelectors.Slot.MODEL);
        assertNotNull(TuiSelectors.Slot.SESSION);
        assertNotNull(TuiSelectors.Slot.THEME);
        assertNotNull(TuiSelectors.Slot.SETTINGS);
        assertNotNull(TuiSelectors.Slot.LOGIN);
        assertNotNull(TuiSelectors.Slot.FORK);
        assertNotNull(TuiSelectors.Slot.PALETTE);
    }

    @Test
    void handleEnterCallsPaletteConfirm() {
        var confirmCount = new int[1];
        var redrawCount = new int[1];
        var selectors = new TuiSelectors();
        selectors.bind(TuiSelectors.Slot.PALETTE, () -> confirmCount[0]++);
        var items = List.<SelectorItem>of(new CompletionItem("/help", "[cmd] Show commands"));
        selectors.show(TuiSelectors.Slot.PALETTE, items);

        assertTrue(selectors.handle(Key.Special.ENTER, () -> redrawCount[0]++));
        assertEquals(1, confirmCount[0]);
        assertEquals(1, redrawCount[0]);
    }

    @Test
    void showWithLoginProviderItems() {
        var selectors = new TuiSelectors();
        var items = List.<SelectorItem>of(new LoginProviderItem("openai", "OpenAI", "oauth", false));
        assertDoesNotThrow(() -> selectors.show(TuiSelectors.Slot.LOGIN, items));
    }

    @Test
    void showWithForkMessageItems() {
        var selectors = new TuiSelectors();
        var msg = new Message.User("content");
        var items = List.<SelectorItem>of(new ForkMessageItem(1, msg));
        assertDoesNotThrow(() -> selectors.show(TuiSelectors.Slot.FORK, items));
    }
}
