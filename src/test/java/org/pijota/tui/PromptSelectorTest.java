package org.pijota.tui;

import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PromptSelectorTest {

    private static List<String> visible(PromptSelector s) {
        return s.renderLines(AnsiTheme.dark()).stream().map(AnsiStripper::strip).toList();
    }

    @Test
    void inactiveByDefaultAndRendersNothing() {
        var s = new PromptSelector();
        assertFalse(s.active());
        assertTrue(s.renderLines(AnsiTheme.dark()).isEmpty());
    }

    @Test
    void enterConfirmsHighlightedIndexAndDeactivates() {
        var s = new PromptSelector();
        var chosen = new AtomicInteger(-1);
        s.show("pick", List.of("a", "b", "c"), chosen::set);

        assertTrue(s.handleKey(Key.Special.DOWN));
        assertTrue(s.handleKey(Key.Special.DOWN));
        assertTrue(s.handleKey(Key.Special.ENTER));

        assertEquals(2, chosen.get());
        assertFalse(s.active());
    }

    @Test
    void pageDownAndPageUpJumpByAWindow() {
        var s = new PromptSelector();
        var chosen = new AtomicInteger(-1);
        s.show("pick", IntStream.range(0, 20).mapToObj(i -> "item" + i).toList(), chosen::set);

        assertTrue(s.handleKey(Key.Special.PG_DOWN)); // 0 → 8
        assertTrue(s.handleKey(Key.Special.PG_DOWN)); // 8 → 16
        assertTrue(s.handleKey(Key.Special.PG_UP)); // 16 → 8
        assertTrue(s.handleKey(Key.Special.ENTER));

        assertEquals(8, chosen.get());
    }

    @Test
    void escCancelsWithoutInvokingCallback() {
        var s = new PromptSelector();
        var chosen = new AtomicInteger(-1);
        s.show("pick", List.of("a", "b"), chosen::set);

        assertTrue(s.handleKey(Key.Special.ESC));

        assertEquals(-1, chosen.get());
        assertFalse(s.active());
    }

    @Test
    void navigationClampsAtBounds() {
        var s = new PromptSelector();
        var chosen = new AtomicInteger(-1);
        s.show("pick", List.of("a", "b"), chosen::set);

        s.handleKey(Key.Special.UP); // already at top
        s.handleKey(Key.Special.DOWN);
        s.handleKey(Key.Special.DOWN); // past bottom, clamps
        s.handleKey(Key.Special.DOWN);
        s.handleKey(Key.Special.ENTER);

        assertEquals(1, chosen.get());
    }

    @Test
    void keysAreIgnoredWhenInactive() {
        var s = new PromptSelector();
        assertFalse(s.handleKey(Key.Special.ENTER));
        assertFalse(s.handleKey(Key.Special.DOWN));
    }

    @Test
    void rendersTitleHighlightAndHint() {
        var s = new PromptSelector();
        s.show("logs", List.of("first", "second"), i -> {});

        var lines = visible(s);
        assertTrue(lines.stream().anyMatch(l -> l.contains("logs")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains(">") && l.contains("first")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("navigate")), lines.toString());
    }

    @Test
    void lineCountMatchesRenderedRowsSoTheRegionReservesEnoughSpace() {
        var s = new PromptSelector();
        assertEquals(0, s.lineCount(), "inactive selector occupies no rows");

        // A long list: every option row must be accounted for, not capped (the bug that hid options).
        var items = IntStream.range(0, 30).mapToObj(i -> "item-" + i).toList();
        s.show("big", items, i -> {});
        assertEquals(visible(s).size(), s.lineCount(), "height budget must match the actual rendered rows");

        for (int i = 0; i < 20; i++)
            s.handleKey(Key.Special.DOWN);
        assertEquals(visible(s).size(), s.lineCount(), "height stays in sync after scrolling");

        // A short list (no more-markers) must also agree.
        s.show("two", List.of("a", "b"), i -> {});
        assertEquals(visible(s).size(), s.lineCount());
    }

    @Test
    void longListScrollsToKeepHighlightVisibleWithMoreCounts() {
        var s = new PromptSelector();
        var items = IntStream.range(0, 30).mapToObj(i -> "item-" + i).toList();
        s.show("big", items, i -> {});

        // Move well past the initial 8-row window.
        for (int i = 0; i < 20; i++)
            s.handleKey(Key.Special.DOWN);

        var lines = visible(s);
        // The highlighted row (item-20) must be on screen even though it is far down the list.
        assertTrue(lines.stream().anyMatch(l -> l.contains(">") && l.contains("item-20")), lines.toString());
        // Items above and below the window are summarized, not dropped silently.
        assertTrue(lines.stream().anyMatch(l -> l.contains("↑") && l.contains("more")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("↓") && l.contains("more")), lines.toString());
    }
}
