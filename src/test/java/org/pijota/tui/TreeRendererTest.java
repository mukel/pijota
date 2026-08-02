package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TreeRendererTest {

    private static AnsiTheme fakeTheme() {
        return AnsiTheme.dark();
    }

    private static Map<String, Object> e(String id, String parentId, String type, String role, String content) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        if (parentId != null)
            m.put("parentId", parentId);
        m.put("type", type);
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> eWithToolCalls(String id, String parentId, String role, String content,
                                    List<Map<String, Object>> toolCalls) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        if (parentId != null)
            m.put("parentId", parentId);
        m.put("type", "message");
        m.put("role", role);
        m.put("content", content);
        if (toolCalls != null)
            m.put("tool_calls", toolCalls);
        return m;
    }

    private static Map<String, Object> eToolResult(String id, String parentId, String toolName, String content,
                                    boolean isError) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        if (parentId != null)
            m.put("parentId", parentId);
        m.put("type", "message");
        m.put("role", "tool_result");
        m.put("tool_name", toolName);
        m.put("content", content);
        m.put("is_error", isError);
        return m;
    }

    private static Map<String, Object> eCompaction(String id, String parentId, int compactedCount) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        if (parentId != null)
            m.put("parentId", parentId);
        m.put("type", "compaction");
        m.put("compactedCount", compactedCount);
        return m;
    }

    @Test
    void renderEmptyTreeShowsNoTurns() {
        var renderer = new TreeRenderer(fakeTheme());
        var lines = renderer.render(List.of(), List.of(), null);
        assertTrue(lines.stream().anyMatch(l -> l.contains("no turns yet")), lines.toString());
    }

    /**
     * One panel header, like every other panel. The tree used to open with a Title Case heading, a stats line and then
     * a second "turns" heading -- three rows of chrome and a grammar of its own, where /context and /usage say the same
     * kind of thing on one row.
     */
    @Test
    void theTreeOpensLikeEveryOtherPanel() {
        var lines = new TreeRenderer(fakeTheme()).render(List.of(), List.of(), null);
        assertEquals("", lines.get(0), "a panel claims its line of air");
        String header = org.pijota.testkit.AnsiStripper.strip(lines.get(1));
        assertEquals("tree  0 turns · 0 entries · /fork N", header,
                                        "lowercase title, then ·-joined chips, like every other panel");
        assertFalse(String.join("\n", lines).contains("Conversation Tree"), "no Title Case heading survives");
    }

    @Test
    void renderSingleTurnProducesOutput() {
        var renderer = new TreeRenderer(fakeTheme());
        var entry = e("u1", null, "message", "user", "hello");
        var entries = List.of(entry);
        var lines = renderer.render(entries, entries, "u1");
        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().anyMatch(l -> l.contains("hello")));
    }

    @Test
    void renderShowsTokenEstimate() {
        var renderer = new TreeRenderer(fakeTheme());
        var entries = List.of(e("u1", null, "message", "user", "x".repeat(40)),
                                        e("a1", "u1", "message", "assistant", "y".repeat(40)));
        var all = String.join("\n", renderer.render(entries, entries, "a1"));
        assertTrue(all.contains("tok"), "header and turn should show a ~token estimate: " + all);
    }

    @Test
    void renderShowsRelativeTimestampWhenPresent() {
        var renderer = new TreeRenderer(fakeTheme());
        var u = e("u1", null, "message", "user", "hello");
        u.put("timestamp", Instant.now().toString());
        var all = String.join("\n", renderer.render(List.of(u), List.of(u), "u1"));
        assertTrue(all.contains("ago") || all.contains("just now"), all);
    }

    @Test
    void linearConversationRendersFlatWithoutBranchConnectors() {
        var renderer = new TreeRenderer(fakeTheme());
        var entries = List.of(e("u1", null, "message", "user", "one"), e("a1", "u1", "message", "assistant", "a"),
                                        e("u2", "a1", "message", "user", "two"),
                                        e("a2", "u2", "message", "assistant", "b"),
                                        e("u3", "a2", "message", "user", "three"));
        var lines = renderer.render(entries, entries, "u3");

        assertTrue(lines.stream().noneMatch(l -> l.contains("├") || l.contains("└") || l.contains("┆")),
                                        "a linear conversation must not draw branch connectors");
        assertTrue(lines.stream().anyMatch(l -> l.contains("one")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("three")));
    }

    @Test
    void forkIndentsOnlyAtTheBranchPoint() {
        var renderer = new TreeRenderer(fakeTheme());
        // u1 → a1, then a1 forks into two user turns
        var entries = List.of(e("u1", null, "message", "user", "root"), e("a1", "u1", "message", "assistant", "resp"),
                                        e("u2a", "a1", "message", "user", "branchA"),
                                        e("u2b", "a1", "message", "user", "branchB"));
        var lines = renderer.render(entries, entries, "u2b");

        assertTrue(lines.stream().anyMatch(l -> l.contains("├")), "the fork should draw branch connectors");
        assertTrue(lines.stream().anyMatch(l -> l.contains("└")));
        String rootLine = lines.stream().filter(l -> l.contains("root")).findFirst().orElseThrow();
        assertFalse(rootLine.contains("├") || rootLine.contains("└"), "the pre-fork turn stays flat: " + rootLine);
    }

    @Test
    void renderMultiTurnWithBranching() {
        var renderer = new TreeRenderer(fakeTheme());
        var entries = List.of(e("u1", null, "message", "user", "first query"),
                                        e("a1", "u1", "message", "assistant", "answer 1"),
                                        e("u2", "a1", "message", "user", "second query"),
                                        e("a2", "u2", "message", "assistant", "answer 2"));
        var lines = renderer.render(entries, entries, "a2");
        assertTrue(lines.stream().anyMatch(l -> l.contains("first query")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("second query")));
    }

    @Test
    void renderIncludesCurrentMarker() {
        var renderer = new TreeRenderer(fakeTheme());
        var entry = e("u1", null, "message", "user", "active query");
        var entries = List.of(entry);
        var lines = renderer.render(entries, entries, "u1");
        assertTrue(lines.stream().anyMatch(l -> l.contains("→")));
    }

    @Test
    void renderTurnWithToolCallsShowsCount() {
        var renderer = new TreeRenderer(fakeTheme());
        var toolCalls = List.<Map<String, Object>>of(Map.of("id", "c1", "name", "read", "arguments", "{}"));
        var entries = List.of(e("u1", null, "message", "user", "query"),
                                        eWithToolCalls("a1", "u1", "assistant", "", toolCalls),
                                        eToolResult("t1", "a1", "read", "ok", false));
        var lines = renderer.render(entries, entries, "a1");
        assertTrue(lines.stream().anyMatch(l -> l.contains("tool")));
    }

    @Test
    void renderTurnWithErrorsShowsCount() {
        var renderer = new TreeRenderer(fakeTheme());
        var toolCalls = List.<Map<String, Object>>of(Map.of("id", "c1", "name", "bash", "arguments", "{}"));
        var entries = List.of(e("u1", null, "message", "user", "bad command"),
                                        eWithToolCalls("a1", "u1", "assistant", "", toolCalls),
                                        eToolResult("t1", "a1", "bash", "command not found", true));
        var lines = renderer.render(entries, entries, "a1");
        assertTrue(lines.stream().anyMatch(l -> l.contains("error")));
    }

    @Test
    void renderWithCompactionShowsCompacted() {
        var renderer = new TreeRenderer(fakeTheme());
        var entries = List.of(e("u1", null, "message", "user", "old"), eCompaction("c1", "u1", 5));
        var lines = renderer.render(entries, List.of(), null);
        assertTrue(lines.stream().anyMatch(l -> l.contains("compacted")));
    }

    @Test
    void renderShowsTurnCount() {
        var renderer = new TreeRenderer(fakeTheme());
        var entries = List.of(e("u1", null, "message", "user", "q1"), e("u2", "u1", "message", "user", "q2"));
        var lines = renderer.render(entries, entries, "u2");
        assertTrue(lines.stream().anyMatch(l -> l.contains("turns")));
    }

    @Test
    void entryIdExtractsStringId() {
        assertEquals("abc-123", TreeRenderer.entryId(Map.of("id", "abc-123")));
        assertNull(TreeRenderer.entryId(Map.of("id", 42)));
        assertNull(TreeRenderer.entryId(Map.of()));
    }

    @Test
    void isUserEntryDetectsUserMessages() {
        assertTrue(TreeRenderer.isUserEntry(Map.of("type", "message", "role", "user")));
        assertFalse(TreeRenderer.isUserEntry(Map.of("type", "message", "role", "assistant")));
        assertFalse(TreeRenderer.isUserEntry(Map.of("type", "tool_result", "role", "")));
    }

    @Test
    void snippetTruncatesLongText() {
        assertEquals("short", TreeRenderer.snippet("short", 20));
        assertTrue(TreeRenderer.snippet("very long text here yes", 10).endsWith("…"));
        assertTrue(TreeRenderer.snippet("very long text here yes", 10).length() <= 10);
    }

    @Test
    void snippetHandlesNull() {
        assertEquals("", TreeRenderer.snippet(null, 10));
    }

    @Test
    void snippetCollapsesWhitespace() {
        assertEquals("a b c", TreeRenderer.snippet("a   b\nc", 20));
    }
}
