package ai.pijota.tui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptLayoutTest {

    /** A region of fixed height used to make the budget arithmetic explicit. */
    private record FixedRegion(int rows) implements PromptRegion.Custom {
        @Override
        public int height(int width, PromptSnapshot ctx) {
            return rows;
        }

        @Override
        public CursorPos render(java.io.PrintWriter out, int width, ai.pijota.theme.AnsiTheme theme,
                                        PromptSnapshot ctx) {
            return NO_CURSOR;
        }
    }

    private static PromptSnapshot snapshot() {
        return new PromptSnapshot(List.of(""), 0, 0, List.of());
    }

    @Test
    void measuredHeightExcludingSumsEveryOtherRegionPlusSeparator() {
        var viewer = new LogViewerRegion(new LogViewer());
        var layout = new PromptLayout(List.of(new PromptRegion.Editor(), new FixedRegion(4), viewer));

        // Editor is 1 row (empty line) + FixedRegion 4 + the 1-row separator = 6. The viewer is excluded.
        assertEquals(1 + 1 + 4, layout.measuredHeightExcluding(LogViewerRegion.class, 80, snapshot()));
    }

    @Test
    void excludedRegionDoesNotCountEvenWhenActive() {
        var lv = new LogViewer();
        lv.show("t", List.of("a", "b", "c"));
        lv.setViewport(20);
        var viewer = new LogViewerRegion(lv);
        var layout = new PromptLayout(List.of(new FixedRegion(2), viewer));

        // Only the FixedRegion (2) + separator (1) are counted; the active viewer is skipped entirely.
        assertEquals(3, layout.measuredHeightExcluding(LogViewerRegion.class, 80, snapshot()));
    }
}
