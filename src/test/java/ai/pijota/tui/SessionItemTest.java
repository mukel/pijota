package ai.pijota.tui;

import ai.pijota.agent.SessionDescriptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class SessionItemTest {

    @Test
    void fromPathsReadsUserContent(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("chat.jsonl");
        Files.writeString(file, "{\"type\":\"message\",\"role\":\"user\",\"content\":\"hello world\"}\n");

        var items = SessionItem.fromPaths(List.of(file));

        assertEquals(1, items.size());
        var item = items.get(0);
        assertEquals("chat", item.name());
        assertFalse(item.named());
        assertEquals("hello world", item.description());
    }

    @Test
    void fromPathsPrefersSessionInfoName(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("named.jsonl");
        Files.writeString(file, "{\"type\":\"session_info\",\"name\":\"My Session\"}\n"
                                        + "{\"type\":\"message\",\"role\":\"user\",\"content\":\"hello\"}\n");

        var items = SessionItem.fromPaths(List.of(file));

        assertEquals(1, items.size());
        assertEquals("My Session", items.get(0).description());
        assertTrue(items.get(0).named());
    }

    @Test
    void fromPathsCleansSystemRemindersAndNewlines(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("clean.jsonl");
        Files.writeString(file, "{\"type\":\"message\",\"role\":\"user\",\"content\":\"before <system-reminder>secret</system-reminder> after\\nline\"}\n");

        var items = SessionItem.fromPaths(List.of(file));

        var description = items.get(0).description();
        assertFalse(description.contains("<system-reminder>"));
        assertFalse(description.contains("\n"));
        assertTrue(description.contains("before"));
        assertTrue(description.contains("after"));
    }

    @Test
    void fromPathsTruncatesLongDescription(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("long.jsonl");
        Files.writeString(file, "{\"type\":\"message\",\"role\":\"user\",\"content\":\"" + "x".repeat(200) + "\"}\n");

        var items = SessionItem.fromPaths(List.of(file));
        var description = items.get(0).description();

        assertTrue(description.length() < 100, "description should be truncated: " + description);
        assertTrue(description.endsWith("…"));
    }

    @Test
    void fromPathsUnreadableReturnsUnreadable(@TempDir Path tmp) {
        var missing = tmp.resolve("missing.jsonl");

        var items = SessionItem.fromPaths(List.of(missing));

        assertEquals(1, items.size());
        assertEquals("(unreadable)", items.get(0).description());
        assertEquals(0, items.get(0).mtime());
    }

    @Test
    void searchTextAndTitleText() throws Exception {
        var item = new SessionItem(Path.of("/tmp/foo.jsonl"), "foo", "bar", 0, false);
        assertTrue(item.searchText().contains("foo"));
        assertTrue(item.searchText().contains("bar"));
        assertEquals("bar", item.titleText());
    }

    @Test
    void detailTextAgesAndTruncatesName() {
        long now = System.currentTimeMillis();
        var justNow = new SessionItem(Path.of("/tmp/a.jsonl"), "a", "d", now, false);
        assertTrue(justNow.detailText().contains("just now"));

        var minutes = new SessionItem(Path.of("/tmp/a.jsonl"), "a", "d", now - 2 * 60_000, false);
        assertTrue(minutes.detailText().contains("m ago"));

        var hours = new SessionItem(Path.of("/tmp/a.jsonl"), "a", "d", now - 2 * 3_600_000, false);
        assertTrue(hours.detailText().contains("h ago"));

        var days = new SessionItem(Path.of("/tmp/a.jsonl"), "a", "d", now - 2 * 86_400_000, false);
        assertTrue(days.detailText().contains("d ago"));

        var unknown = new SessionItem(Path.of("/tmp/a.jsonl"), "a", "d", 0, false);
        assertTrue(unknown.detailText().contains("unknown"));

        var longName = new SessionItem(Path.of("/tmp/a.jsonl"), "verylongsessionname", "d", now, false);
        assertTrue(longName.detailText().contains("verylongsessio"));
        assertFalse(longName.detailText().contains("verylongsessionname"));
    }

    @Test
    void cleanReturnsNullForNull() {
        assertNull(SessionDescriptor.clean(null));
    }

    @Test
    void cleanReturnsNullForBlank() {
        assertNull(SessionDescriptor.clean("   "));
    }

    @Test
    void cleanStripsSystemReminders() {
        assertEquals("hello world", SessionDescriptor.clean("hello <system-reminder>secret</system-reminder> world"));
    }

    @Test
    void cleanReplacesNewlinesWithSpaces() {
        assertEquals("line1 line2", SessionDescriptor.clean("line1\nline2"));
    }

    @Test
    void cleanCollapsesWhitespace() {
        assertEquals("a b c", SessionDescriptor.clean("a   b    c"));
    }

    @Test
    void cleanTrimsLeadingAndTrailingSpace() {
        assertEquals("text", SessionDescriptor.clean("  text  "));
    }

    @Test
    void truncateReturnsShortStringAsIs() {
        assertEquals("short", SessionDescriptor.truncate("short"));
    }

    @Test
    void truncateTruncatesLongStringAtWordBoundary() {
        String longText = "a".repeat(90);
        String result = SessionDescriptor.truncate(longText);
        assertTrue(result.length() <= 81);
        assertTrue(result.endsWith("…"));
    }

    @Test
    void descriptionFromLineParsesUserMessage() {
        var result = SessionDescriptor.parseLine(
                                        "{\"type\":\"message\",\"role\":\"user\",\"content\":\"hello world\"}");
        assertNotNull(result);
        assertEquals("hello world", result.firstUser());
        assertNull(result.sessionName());
    }

    @Test
    void descriptionFromLineParsesSessionInfo() {
        var result = SessionDescriptor.parseLine("{\"type\":\"session_info\",\"name\":\"My Session\"}");
        assertNotNull(result);
        assertNull(result.firstUser());
        assertEquals("My Session", result.sessionName());
    }

    @Test
    void descriptionFromLineReturnsNullForNonMessage() {
        var result = SessionDescriptor.parseLine("{\"type\":\"tool_result\"}");
        assertNull(result);
    }

    @Test
    void descriptionFromLineReturnsNullForAssistantMessage() {
        var result = SessionDescriptor.parseLine(
                                        "{\"type\":\"message\",\"role\":\"assistant\",\"content\":\"I am helping\"}");
        assertNull(result);
    }

    @Test
    void descriptionFromLineReturnsNullForInvalidJson() {
        assertNull(SessionDescriptor.parseLine("not json"));
    }
}
