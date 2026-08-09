package org.pijota.agent;

import org.pijota.messages.Message;
import org.pijota.messages.ToolCall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        // resolving by a literal like "a" was flaky: it only reached the ambiguous branch when exactly the
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

    // -- Crash safety: torn-tail repair (a crashed append leaves a partial final line) --

    @Test
    void tornTailMalformedFinalLineIsTruncatedOnLoad() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = manager.createSession(tempDir.toString());
        manager.appendMessage(file, new Message.User("hello"));
        Files.writeString(file, "{\"id\":\"partial", StandardOpenOption.APPEND); // crash mid-append: no newline

        var loaded = new SessionManager(tempDir).loadMessages(file); // fresh manager = new process resuming

        assertEquals(1, loaded.size(), "the acknowledged entry survives, the torn tail does not");
        String content = Files.readString(file);
        assertTrue(content.endsWith("\n"), "the repaired file is newline-terminated again");
        assertFalse(content.contains("partial"), "the never-acknowledged tail is gone");
    }

    @Test
    void tornTailCompleteJsonWithoutNewlineIsCompleted() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = manager.createSession(tempDir.toString());
        var first = new Message.User("hello");
        manager.appendMessage(file, first);
        // The crash cut exactly between the JSON and its newline: the entry itself is intact.
        String tail = "{\"id\":\"" + UUID.randomUUID() + "\",\"parentId\":\"" + first.id()
                                        + "\",\"type\":\"message\",\"role\":\"user\",\"content\":\"tail\"}";
        Files.writeString(file, tail, StandardOpenOption.APPEND);

        var loaded = new SessionManager(tempDir).loadMessages(file);

        assertEquals(2, loaded.size(), "an intact unterminated entry is kept, not dropped");
        assertEquals("tail", ((Message.User) loaded.get(1)).content());
        assertTrue(Files.readString(file).endsWith("\n"), "the missing newline was added");
    }

    @Test
    void appendAfterTornTailDoesNotMergeLines() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = manager.createSession(tempDir.toString());
        manager.appendMessage(file, new Message.User("one"));
        Files.writeString(file, "{\"garbage", StandardOpenOption.APPEND);

        // Resume: load (repairs the tail), then append; the new entry must land on its own line, not be
        // concatenated onto the partial one (which would corrupt both entries and orphan their children).
        var resumed = new SessionManager(tempDir);
        resumed.loadMessages(file);
        resumed.appendMessage(file, new Message.User("two"));

        var reloaded = new SessionManager(tempDir).loadMessages(file);
        assertEquals(2, reloaded.size(), "both entries survive as separate, parseable lines");
        assertEquals("two", ((Message.User) reloaded.get(1)).content());
    }

    @Test
    void midFileMalformedLineIsSkippedNotFatal() throws Exception {
        var file = tempDir.resolve("mid.jsonl");
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        Files.writeString(file, "{\"type\":\"session\",\"version\":3}\n" //
                                        + "{\"id\":\"" + a
                                        + "\",\"type\":\"message\",\"role\":\"user\",\"content\":\"one\"}\n" //
                                        + "not json at all\n" // mid-file corruption: skipped with a warning, not fatal
                                        + "{\"id\":\"" + b + "\",\"parentId\":\"" + a
                                        + "\",\"type\":\"message\",\"role\":\"user\",\"content\":\"two\"}\n");

        var loaded = new SessionManager(tempDir).loadMessages(file);

        assertEquals(2, loaded.size(), "the valid entries load; the corrupt line is skipped");
    }

    // -- Crash safety: dangling tool calls (crash between the assistant batch and its results) --

    @Test
    void danglingToolCallsGetSyntheticErrorResults() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = tempDir.resolve("dangling.jsonl");
        manager.saveMessages(file, List.of(new Message.User("q"),
                                        new Message.Assistant("a", List.of(new ToolCall("t1", "read", "{}"),
                                                                        new ToolCall("t2", "bash", "{}")), null),
                                        new Message.ToolResult("t1", "read", "ok", false)), "/ws");

        var loaded = new SessionManager(tempDir).loadMessages(file);

        assertEquals(4, loaded.size(), "user, assistant, surviving result, synthetic result");
        assertEquals("t1", ((Message.ToolResult) loaded.get(2)).toolCallId(), "surviving results keep their order");
        var synthetic = assertInstanceOf(Message.ToolResult.class, loaded.get(3));
        assertEquals("t2", synthetic.toolCallId());
        assertTrue(synthetic.isError(), "a synthesized result is an error, not a fake success");
        assertTrue(synthetic.content().contains("interrupted"), synthetic.content());
    }

    @Test
    void danglingToolCallsMidContextAreReconciledInPlace() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = tempDir.resolve("midctx.jsonl");
        // The user continued past the crash: the synthetic result must be inserted after its batch,
        // not at the tail, so call/result pairing holds for the provider.
        manager.saveMessages(file, List.of(new Message.User("q"),
                                        new Message.Assistant("a", List.of(new ToolCall("t1", "read", "{}")), null),
                                        new Message.User("continued anyway")), "/ws");

        var loaded = new SessionManager(tempDir).loadMessages(file);

        assertEquals(4, loaded.size());
        var synthetic = assertInstanceOf(Message.ToolResult.class, loaded.get(2), "inserted right after its batch");
        assertEquals("t1", synthetic.toolCallId());
        assertTrue(synthetic.isError());
        assertInstanceOf(Message.User.class, loaded.get(3), "the continuation stays after the repaired batch");
    }

    @Test
    void fullyAnsweredBatchesAreUntouched() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = tempDir.resolve("healthy.jsonl");
        manager.saveMessages(file, List.of(new Message.User("q"),
                                        new Message.Assistant("a", List.of(new ToolCall("t1", "read", "{}")), null),
                                        new Message.ToolResult("t1", "read", "ok", false)), "/ws");

        var loaded = new SessionManager(tempDir).loadMessages(file);

        assertEquals(3, loaded.size(), "no spurious synthesis on a healthy session");
        assertTrue(loaded.stream().noneMatch(m -> m instanceof Message.ToolResult tr && tr.isError()));
    }

    /**
     * The walk ends on a missing parent exactly as it ends on the root, so the two are indistinguishable from the
     * returned list. It returns what it could reach rather than throwing: the file is the user's only copy, and one
     * real session on disk (2 MB, no header, oldest entry naming a parent that exists nowhere) opens fine this way.
     * Refusing would lock them out of it; everything present is still returned.
     */
    @Test
    void aBranchWalkStopsAtAMissingParentAndKeepsWhatItReached() {
        var entries = new java.util.LinkedHashMap<String, Map<String, Object>>();
        entries.put("b", Map.of("id", "b", "parentId", "gone-a"));
        entries.put("c", Map.of("id", "c", "parentId", "b"));

        var path = SessionManager.buildBranchPath(entries, "c");

        assertEquals(List.of("b", "c"), path.stream().map(e -> e.get("id")).toList(),
                                        "oldest first, and nothing that is in the file is dropped");
    }

    /** The ordinary case: a chain that reaches a root returns the whole conversation. */
    @Test
    void aWholeBranchReachesItsRoot() {
        var entries = new java.util.LinkedHashMap<String, Map<String, Object>>();
        entries.put("a", new java.util.HashMap<>(Map.of("id", "a")));
        entries.put("b", Map.of("id", "b", "parentId", "a"));

        var path = SessionManager.buildBranchPath(entries, "b");

        assertEquals(List.of("a", "b"), path.stream().map(e -> e.get("id")).toList());
        assertNull(path.getFirst().get("parentId"), "the oldest entry is a root, which is how a whole branch ends");
    }

    /** A parent cycle terminates instead of hanging; a corrupt file must not spin the loader forever. */
    @Test
    void aParentCycleTerminates() {
        var entries = Map.<String, Map<String, Object>>of("x", Map.of("id", "x", "parentId", "y"), "y",
                                        Map.of("id", "y", "parentId", "x"));

        assertEquals(2, SessionManager.buildBranchPath(entries, "x").size());
    }

    /**
     * Only what nobody can reproduce is worth an fsync. A reply can be asked for again and a tool result re-run, and
     * those are the frequent ones -- several a turn, on the turn thread. What the user typed arrives at human speed and
     * is gone for good if the machine loses power before it reaches the platter.
     */
    @Test
    void onlyTheUsersOwnWordsAreWorthAnFsync() {
        assertTrue(SessionManager.isUnreproducible(new Message.User("what I typed")));

        assertFalse(SessionManager.isUnreproducible(
                                        new Message.Assistant("a reply that can be asked for again", List.of(), null)));
        assertFalse(SessionManager.isUnreproducible(
                                        new Message.ToolResult("tc1", "bash", "output that can be re-run", false)));
        assertFalse(SessionManager.isUnreproducible(new Message.Custom("note", "a frontend line")));
    }

    /**
     * Falling back to the newest entry is what lets a session open at all when the recorded leaf is gone -- a torn
     * tail, a hand-edited file, an id belonging to another session. The rule was written once here and again in the
     * exporter, spelled slightly differently.
     */
    @Test
    void anUnknownLeafFallsBackToTheLastEntryWritten() {
        var entries = new java.util.LinkedHashMap<String, java.util.Map<String, Object>>();
        entries.put("a", java.util.Map.of("id", "a"));
        entries.put("b", java.util.Map.of("id", "b"));
        entries.put("c", java.util.Map.of("id", "c"));

        assertEquals("b", SessionManager.effectiveLeaf(entries, "b"), "a leaf that exists is the leaf");
        assertEquals("c", SessionManager.effectiveLeaf(entries, "gone"), "an unknown leaf falls back to the newest");
        assertEquals("c", SessionManager.effectiveLeaf(entries, null), "and so does no leaf at all");
        assertNull(SessionManager.effectiveLeaf(new java.util.LinkedHashMap<>(), "anything"),
                                        "nothing to fall back to in an empty file");
    }
}
