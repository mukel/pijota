package org.pijota.agent;

import org.pijota.messages.Message;
import org.pijota.messages.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void deleteIfEmptyRemovesHeaderOnlySessionsButKeepsConversations() throws Exception {
        var manager = new SessionManager(tempDir);

        var empty = manager.createSession(tempDir.toString());
        manager.appendCacheMetadata(empty, "sys", "hash", "{}", "openai", "gpt-4");
        assertTrue(Files.exists(empty));
        manager.deleteIfEmpty(empty);
        assertFalse(Files.exists(empty), "header/metadata-only session should be deleted");

        var used = manager.createSession(tempDir.toString());
        manager.appendMessage(used, new Message.User("hello"));
        manager.deleteIfEmpty(used);
        assertTrue(Files.exists(used), "session with a message must be kept");
    }

    @Test
    void savesAndLoadsConversationMessagesWithoutSystemPrompts() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = tempDir.resolve("session.jsonl");
        var messages = List.<Message>of(new Message.System("system prompt"), new Message.User("hello"),
                                        new Message.Assistant("hi", List.of(new ToolCall("tc1", "read", "{}")),
                                                                        "thinking"),
                                        new Message.ToolResult("tc1", "read", "contents", false));

        manager.saveMessages(file, messages, "/workspace");
        var loaded = manager.loadMessages(file);

        assertEquals(3, loaded.size());
        assertInstanceOf(Message.User.class, loaded.get(0));
        assertEquals("hello", ((Message.User) loaded.get(0)).content());

        var assistant = assertInstanceOf(Message.Assistant.class, loaded.get(1));
        assertEquals("hi", assistant.content());
        assertEquals("thinking", assistant.thinking());
        assertEquals("read", assistant.toolCalls().getFirst().name());

        var tool = assertInstanceOf(Message.ToolResult.class, loaded.get(2));
        assertEquals("tc1", tool.toolCallId());
        assertEquals("contents", tool.content());
    }

    @Test
    void richMessageTypesRoundTripThroughTheSessionFile() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = tempDir.resolve("rich.jsonl");
        var messages = List.<Message>of(new Message.System("system prompt"), new Message.User("q"),
                                        new Message.Assistant("a", List.of(new ToolCall("t1", "bash",
                                                                        "{\"command\":\"ls\"}"),
                                                                        new ToolCall("t2", "read", "{}")), ""),
                                        new Message.ToolResult("t1", "bash", "boom", true), // an error result
                                        new Message.CompactionSummary("a compaction summary"),
                                        new Message.BranchSummary("a branch summary"),
                                        new Message.Custom("note", "a side note", Map.of("silent", true)));

        manager.saveMessages(file, messages, "/ws");
        var loaded = manager.loadMessages(file);

        var asst = (Message.Assistant) loaded.stream().filter(m -> m instanceof Message.Assistant).findFirst()
                                        .orElseThrow();
        assertEquals(2, asst.toolCalls().size(), "multiple tool calls round-trip");
        var tr = (Message.ToolResult) loaded.stream().filter(m -> m instanceof Message.ToolResult).findFirst()
                                        .orElseThrow();
        assertTrue(tr.isError(), "the error flag round-trips");
        var custom = (Message.Custom) loaded.stream().filter(m -> m instanceof Message.Custom).findFirst()
                                        .orElseThrow();
        assertEquals("a side note", custom.content());
    }

    @Test
    void userAttachmentsRoundTripThroughTheSessionFile() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = tempDir.resolve("attachments.jsonl");
        var att = new Message.Attachment("image/png", "QQ==", "x.png");
        manager.saveMessages(file, List.<Message>of(new Message.User("look", List.of(att))), "/workspace");

        var loaded = manager.loadMessages(file);
        var user = assertInstanceOf(Message.User.class, loaded.getFirst());
        assertEquals(1, user.attachments().size());
        var roundTripped = user.attachments().getFirst();
        assertEquals("image/png", roundTripped.mime());
        assertEquals("QQ==", roundTripped.base64());
        assertEquals("x.png", roundTripped.filename());
    }

    @Test
    void appendsAndReadsSessionMetadataFromCurrentBranch() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = manager.createSession("/workspace/project");

        manager.appendMessage(file, new Message.User("hello"));
        manager.appendSessionInfo(file, "Demo");
        manager.appendModelChange(file, "openai", "gpt-test", 1234);
        manager.appendThinkingLevelChange(file, "HIGH");
        manager.appendCacheMetadata(file, "system", "hash", "{}", "openai", "gpt-test");

        assertEquals("Demo", manager.sessionName(file));
        assertEquals("HIGH", manager.latestThinkingLevel(file));

        var model = manager.latestModelSelection(file);
        assertNotNull(model);
        assertEquals("openai", model.provider());
        assertEquals("gpt-test", model.model());
        assertEquals(1234, model.contextWindow());

        var cache = manager.latestCacheMetadata(file);
        assertNotNull(cache);
        assertEquals("system", cache.systemPrompt());
        assertEquals("hash", cache.toolManifestHash());
    }

    @Test
    void resolvesSessionsByUniqueShortId() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = manager.createSession("/workspace/project");
        var id = file.getFileName().toString().replace(".jsonl", "");

        assertEquals(file, manager.resolveSession("/workspace/project", id.substring(0, 8)));
    }

    @Test
    void createSessionWritesHeaderAndCleansStaleTmpFiles() throws Exception {
        var manager = new SessionManager(tempDir);
        var parent = tempDir.resolve("workspace_project");
        Files.createDirectories(parent);
        Files.writeString(parent.resolve("stale.tmp"), "x");

        var file = manager.createSession("/workspace/project");

        assertTrue(Files.exists(file));
        assertFalse(Files.exists(parent.resolve("stale.tmp")));
        var firstLine = Files.readString(file).lines().findFirst().orElseThrow();
        assertTrue(firstLine.contains("\"type\":\"session\""));
    }

    @Test
    void forkSessionCopiesBranchToNewFile() throws Exception {
        var manager = new SessionManager(tempDir);
        var source = manager.createSession("/workspace/project");
        manager.appendMessage(source, new Message.User("hello"));
        manager.appendMessage(source, new Message.User("world"));
        var leafId = manager.leafId();

        var forked = manager.forkSession(source, leafId, "/workspace/project");

        assertNotEquals(source, forked);
        var loaded = manager.loadMessages(forked);
        assertEquals(2, loaded.size());
    }

    @Test
    void migrateCopiesBranchWhenLeafIdProvided() throws Exception {
        var manager = new SessionManager(tempDir);
        var source = manager.createSession("/workspace/project");
        manager.appendMessage(source, new Message.User("hello"));
        var leafId = manager.leafId();

        var migrated = manager.migrate(source, leafId, "/workspace/project");

        var loaded = manager.loadMessages(migrated);
        assertEquals(1, loaded.size());
    }

    @Test
    void migrateCopiesAllEntriesWhenLeafIdIsNull() throws Exception {
        var manager = new SessionManager(tempDir);
        var source = manager.createSession("/workspace/project");
        manager.appendMessage(source, new Message.User("hello"));
        manager.appendMessage(source, new Message.User("world"));

        var migrated = manager.migrate(source, null, "/workspace/project");

        var loaded = manager.loadMessages(migrated);
        assertEquals(2, loaded.size());
    }

    @Test
    void latestSessionThrowsWhenNoSessionExists() {
        var manager = new SessionManager(tempDir);
        assertThrows(IOException.class, () -> manager.latestSession("/workspace/project", null));
    }

    @Test
    void resolveSessionReturnsDirectPathWhenItExists() throws Exception {
        var manager = new SessionManager(tempDir);
        var direct = tempDir.resolve("direct.jsonl");
        Files.writeString(direct, "{\"type\":\"session\"}\n");

        assertEquals(direct, manager.resolveSession("/workspace/project", direct.toString()));
    }

    @Test
    void resolveSessionThrowsWhenAmbiguous() throws Exception {
        var manager = new SessionManager(tempDir);
        // Two sessions sharing a known prefix so it reliably matches both. Session ids are random UUIDs, so
        // resolving by a literal like "a" was flaky — it only reached the ambiguous branch when exactly the
        // right number of UUIDs happened to start with that letter.
        Path dir = manager.createSession("/workspace/project").getParent();
        Files.writeString(dir.resolve("shared-one.jsonl"), "{\"type\":\"session\"}\n");
        Files.writeString(dir.resolve("shared-two.jsonl"), "{\"type\":\"session\"}\n");

        assertThrows(IOException.class, () -> manager.resolveSession("/workspace/project", "shared-"));
    }

    @Test
    void listSessionsReturnsEmptyWhenDirectoryMissing() {
        var manager = new SessionManager(tempDir);
        assertTrue(manager.listSessions("/nonexistent/cwd").isEmpty());
    }

    @Test
    void saveMessagesPerformsAtomicWriteAndSetsLeafId() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = tempDir.resolve("atomic.jsonl");
        var messages = List.<Message>of(new Message.User("hello"), new Message.Assistant("hi", List.of(), null));

        manager.saveMessages(file, messages, "/workspace");

        assertTrue(Files.exists(file));
        assertFalse(Files.exists(Path.of(file.toString() + ".tmp")));
        assertNotNull(manager.leafId());
    }

    @Test
    void loadMessagesReturnsEmptyForMissingFile() throws Exception {
        var manager = new SessionManager(tempDir);
        assertTrue(manager.loadMessages(tempDir.resolve("missing.jsonl")).isEmpty());
    }

    @Test
    void readAllEntriesIgnoresBadLinesAndSessionHeader() throws Exception {
        var file = tempDir.resolve("mixed.jsonl");
        Files.writeString(file, "{\"type\":\"session\",\"id\":\"header\"}\n" + "not valid json\n"
                                        + "{\"id\":\"entry1\",\"type\":\"message\"}\n");

        var entries = SessionManager.readAllEntries(file);

        assertEquals(1, entries.size());
        assertEquals("entry1", entries.keySet().iterator().next());
    }

    @Test
    void sessionNameReturnsNullForBlankName() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = manager.createSession("/workspace/project");
        manager.appendSessionInfo(file, "   ");

        assertNull(manager.sessionName(file));
    }

    @Test
    void latestModelSelectionIgnoresBlankProviderOrModel() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = manager.createSession("/workspace/project");
        manager.appendModelChange(file, "", "gpt-test", 1234);
        manager.appendModelChange(file, "openai", "", 1234);

        assertNull(manager.latestModelSelection(file));
    }

    @Test
    void getBranchAndTreeEntries() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = manager.createSession("/workspace/project");
        manager.appendMessage(file, new Message.User("hello"));

        assertFalse(manager.getBranchEntries(file).isEmpty());
        assertFalse(manager.getTreeEntries(file).isEmpty());
    }

    @Test
    void buildContextWithCompactionAndFirstKeptEntry() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = manager.createSession("/workspace/project");
        manager.appendMessage(file, new Message.User("before"));
        manager.appendMessage(file, new Message.User("first kept"));
        var firstKeptId = manager.leafId();
        manager.appendMessage(file, new Message.User("after"));
        manager.appendCompaction(file, "summary", 1, firstKeptId);

        var loaded = manager.loadMessages(file);

        assertTrue(loaded.stream().anyMatch(m -> m instanceof Message.CompactionSummary));
    }
}
