package ai.pijota.tui;

import ai.pijota.testkit.AnsiStripper;
import ai.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogViewerTest {

    private static LogViewer viewer(int viewport, int count) {
        var v = new LogViewer();
        v.show("agent.log", IntStream.range(0, count).mapToObj(i -> "line" + i).toList());
        v.setViewport(viewport);
        return v;
    }

    private static void type(LogViewer v, String text) {
        for (char c : text.toCharArray())
            v.handleKey(new Key.Char(c));
    }

    private static String visible(LogViewer v) {
        return v.renderLines(AnsiTheme.dark(), 80).stream().map(AnsiStripper::strip).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void liveSourceTailsAppendedLinesWhileFollowing() {
        var backing = new ArrayList<>(List.of("a", "b"));
        var v = new LogViewer();
        v.showLive("live.log", () -> List.copyOf(backing));
        v.setViewport(2);
        assertTrue(visible(v).contains("following"), "live viewer starts in follow mode");
        assertTrue(visible(v).contains("1-2/2"), visible(v));

        backing.add("c");
        backing.add("d");
        v.refresh();

        String out = visible(v);
        assertTrue(out.contains("c") && out.contains("d"), out);
        assertTrue(out.contains("3-4/4"), "follow mode pins the view to the new tail: " + out);
    }

    @Test
    void scrollingUpDetachesFollowAndEndReattaches() {
        var backing = new ArrayList<>(IntStream.range(0, 10).mapToObj(i -> "l" + i).toList());
        var v = new LogViewer();
        v.showLive("live.log", () -> List.copyOf(backing));
        v.setViewport(3);
        assertTrue(visible(v).contains("following"));

        v.handleKey(Key.Special.UP);
        assertFalse(visible(v).contains("following"), "scrolling up detaches follow");

        backing.add("new");
        v.refresh(); // must not yank the detached view back to the bottom
        assertFalse(visible(v).contains("following"));

        v.handleKey(Key.Special.END);
        assertTrue(visible(v).contains("following"), "End re-attaches follow");
    }

    @Test
    void inactiveUntilShownAndClosed() {
        var v = new LogViewer();
        assertFalse(v.active());
        v.show("t", List.of("a"));
        assertTrue(v.active());
        v.close();
        assertFalse(v.active());
    }

    @Test
    void stripsAnsiOnShow() {
        var v = new LogViewer();
        v.show("t", List.of("[31mred[0m [INFO] ok"));
        // [INFO] must survive (only ANSI escapes are stripped, not bracketed text)
        assertTrue(visible(v).contains("red [INFO] ok"), visible(v));
    }

    @Test
    void scrollKeysClampToBounds() {
        var v = viewer(3, 10); // maxTop = 7
        assertEquals(0, v.top());
        v.handleKey(Key.Special.UP); // already at top
        assertEquals(0, v.top());
        v.handleKey(Key.Special.DOWN);
        assertEquals(1, v.top());
        v.handleKey(Key.Special.PG_DOWN);
        assertEquals(4, v.top());
        v.handleKey(Key.Special.END);
        assertEquals(7, v.top());
        v.handleKey(Key.Special.DOWN); // past end clamps
        assertEquals(7, v.top());
        v.handleKey(Key.Special.HOME);
        assertEquals(0, v.top());
    }

    @Test
    void vimKeysScroll() {
        var v = viewer(3, 10);
        type(v, "j");
        assertEquals(1, v.top());
        type(v, "G");
        assertEquals(7, v.top());
        type(v, "k");
        assertEquals(6, v.top());
        type(v, "g");
        assertEquals(0, v.top());
    }

    @Test
    void qClosesViewer() {
        var v = viewer(3, 10);
        assertTrue(v.handleKey(new Key.Char('q')));
        assertFalse(v.active());
    }

    @Test
    void escClosesViewer() {
        var v = viewer(3, 10);
        assertTrue(v.handleKey(Key.Special.ESC));
        assertFalse(v.active());
    }

    @Test
    void searchFindsMatchesAndScrollsToFirst() {
        var v = new LogViewer();
        var lines = new ArrayList<String>();
        for (int i = 0; i < 10; i++)
            lines.add(i == 5 || i == 8 ? "needle here " + i : "line" + i);
        v.show("t", lines);
        v.setViewport(3);

        v.handleKey(new Key.Char('/'));
        assertTrue(v.searching());
        type(v, "needle");
        v.handleKey(Key.Special.ENTER);

        assertFalse(v.searching());
        assertEquals(2, v.matchCount());
        // scrolled to first match (line 5) and it's on screen
        assertTrue(visible(v).contains("needle here 5"), visible(v));

        v.handleKey(new Key.Char('n')); // -> second match (line 8)
        assertTrue(visible(v).contains("needle here 8"), visible(v));
        v.handleKey(new Key.Char('N')); // -> back to first
        assertTrue(visible(v).contains("needle here 5"), visible(v));
    }

    @Test
    void searchInputBackspaceAndEscCancel() {
        var v = viewer(3, 10);
        v.handleKey(new Key.Char('/'));
        type(v, "linX");
        v.handleKey(Key.Special.BACKSPACE); // -> "lin"
        v.handleKey(Key.Special.ENTER);
        assertEquals(10, v.matchCount(), "all 'lineN' lines match 'lin'");

        v.handleKey(new Key.Char('/'));
        type(v, "zzz");
        v.handleKey(Key.Special.ESC); // cancels search input
        assertFalse(v.searching());
        assertEquals(10, v.matchCount(), "prior search retained, input cancelled");
        assertTrue(v.active(), "Esc in search mode cancels input, does not close viewer");
    }

    @Test
    void renderShowsHeaderBodyAndFooter() {
        var v = viewer(20, 2);
        String out = visible(v);
        assertTrue(out.contains("agent.log") && out.contains("2 lines"), out);
        assertTrue(out.contains("line0") && out.contains("line1"), out);
        assertTrue(out.contains("1-2/2"), out);
        assertTrue(out.contains("scroll") && out.contains("type / to search"), out);
    }

    @Test
    void bodyLinesAreFlushLeftSoCopyPasteYieldsRawText() {
        var v = new LogViewer();
        // include a match so the current-match and match styling paths are covered too
        v.show("t", List.of("  indented log line", "another line", "match here", "match here"));
        v.setViewport(20);
        v.handleKey(new Key.Char('/'));
        type(v, "match");
        v.handleKey(Key.Special.ENTER);

        var body = v.renderLines(AnsiTheme.dark(), 80).stream().map(AnsiStripper::strip).filter(
                                        l -> l.contains("log line") || l.contains("another") || l.contains("match"))
                                        .toList();
        assertFalse(body.isEmpty(), "expected body lines");
        // No viewer-added prefix/indent: the log's own leading spaces survive, nothing extra is prepended.
        assertTrue(body.contains("  indented log line"), body.toString());
        assertTrue(body.contains("another line"), body.toString());
        assertTrue(body.contains("match here"), body.toString());
    }

    @Test
    void emptyLogRendersGracefully() {
        var v = new LogViewer();
        v.show("empty", List.of());
        assertEquals(3, v.lineCount());
        assertTrue(visible(v).contains("empty log"), visible(v));
    }
}
