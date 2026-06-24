package ai.pijota.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** SessionDescriptor is the single place every frontend derives a session's label/counts from — pure, core logic. */
class SessionDescriptorTest {

    private static Path sessionFile(Path dir, String name, String... lines) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, java.util.List.of(lines));
        return p;
    }

    // ---- describe(): parse a .jsonl session ----

    @Test
    void describeUsesTheSessionNameWhenNamed(@TempDir Path dir) throws Exception {
        var p = sessionFile(dir, "abc123.jsonl", "{\"type\":\"session_info\",\"name\":\"My Session\"}",
                                        "{\"type\":\"message\",\"role\":\"user\",\"content\":\"hello\"}",
                                        "{\"type\":\"message\",\"role\":\"assistant\",\"content\":\"hi\"}");
        var d = SessionDescriptor.describe(p);

        assertEquals("abc123", d.id(), "id drops the .jsonl extension");
        assertEquals("My Session", d.description());
        assertTrue(d.named());
        assertEquals(2, d.messageCount());
    }

    @Test
    void describeFallsBackToFirstUserMessageWhenUnnamed(@TempDir Path dir) throws Exception {
        var p = sessionFile(dir, "s.jsonl", "{\"type\":\"message\",\"role\":\"user\",\"content\":\"first question\"}",
                                        "{\"type\":\"message\",\"role\":\"assistant\",\"content\":\"an answer\"}");
        var d = SessionDescriptor.describe(p);

        assertFalse(d.named());
        assertEquals("first question", d.description());
    }

    @Test
    void messageCountExcludesToolResultsButTokensIncludeThem(@TempDir Path dir) throws Exception {
        var p = sessionFile(dir, "s.jsonl", "{\"type\":\"message\",\"role\":\"user\",\"content\":\"q\"}",
                                        "{\"type\":\"message\",\"role\":\"assistant\",\"content\":\"a\"}",
                                        "{\"type\":\"message\",\"role\":\"tool\",\"content\":\"tool plumbing output\"}");
        var d = SessionDescriptor.describe(p);

        assertEquals(2, d.messageCount(), "tool entries are plumbing, not conversational turns");
        assertTrue(d.estTokens() > 0, "but their content still counts toward token estimate");
    }

    @Test
    void describeIsResilientToCorruptLinesAndEmptyFiles(@TempDir Path dir) throws Exception {
        var p = sessionFile(dir, "s.jsonl", "not json at all", "", "{\"type\":\"message\",\"role\":\"user\"}");
        var d = SessionDescriptor.describe(p);
        assertEquals("(empty)", d.description(), "no readable user content → (empty)");
        assertEquals(1, d.messageCount());
    }

    // ---- label(): the one-line picker label ----

    @Test
    void labelShowsCountsTokensAndAge() {
        var d = new SessionDescriptor(Path.of("x"), "x", "Fix the parser", false,
                                        System.currentTimeMillis() - 5 * 60_000, 3, 1200);
        String label = d.label();
        assertTrue(label.startsWith("Fix the parser"));
        assertTrue(label.contains("3 msgs"), label);
        assertTrue(label.contains("tok"), label);
        assertTrue(label.contains("5m ago"), label);
    }

    @Test
    void labelUsesSingularForOneMessageAndOmitsCountsWhenEmpty() {
        assertTrue(new SessionDescriptor(Path.of("x"), "x", "d", false, 0, 1, 10).label().contains("1 msg"));
        assertFalse(new SessionDescriptor(Path.of("x"), "x", "d", false, 0, 1, 10).label().contains("1 msgs"));
        assertEquals("(empty)", new SessionDescriptor(Path.of("x"), "x", "(empty)", false, 0, 0, 0).label(),
                                        "0-message sessions show only the description");
    }

    // ---- pure helpers ----

    @Test
    void cleanStripsSystemRemindersAndCollapsesWhitespace() {
        assertEquals("hello world",
                                        SessionDescriptor.clean("hello   <system-reminder>secret</system-reminder>\n world"));
        assertNull(SessionDescriptor.clean("   "));
        assertNull(SessionDescriptor.clean(null));
    }

    @Test
    void truncateCutsAtAWordBoundaryNearTheLimit() {
        String s = "word ".repeat(40); // ~200 chars
        String t = SessionDescriptor.truncate(s);
        assertTrue(t.length() <= 80);
        assertFalse(t.endsWith("wor"), "should not cut mid-word when a nearby boundary exists: " + t);
        assertEquals("short", SessionDescriptor.truncate("short"));
    }

    @Test
    void estimateTokensIsRoughlyCharsOverFour() {
        assertEquals(0, SessionDescriptor.estimateTokens(0));
        assertEquals(1, SessionDescriptor.estimateTokens(1));
        assertEquals(25, SessionDescriptor.estimateTokens(100));
    }

    @Test
    void relativeAgeBuckets() {
        long now = System.currentTimeMillis();
        assertEquals("unknown", SessionDescriptor.relativeAge(0));
        assertEquals("just now", SessionDescriptor.relativeAge(now - 10_000));
        assertEquals("5m ago", SessionDescriptor.relativeAge(now - 5 * 60_000));
        assertEquals("2h ago", SessionDescriptor.relativeAge(now - 2 * 3_600_000));
        assertEquals("3d ago", SessionDescriptor.relativeAge(now - 3 * 86_400_000L));
    }
}
