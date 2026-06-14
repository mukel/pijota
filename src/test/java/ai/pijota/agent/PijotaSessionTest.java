package ai.pijota.agent;

import ai.pijota.config.AppConfig;
import ai.pijota.extensions.ExtensionRegistry;
import ai.pijota.llm.ThinkingLevel;
import ai.pijota.messages.Message;
import ai.pijota.messages.Usage;
import ai.pijota.testkit.FauxLlm;
import ai.pijota.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PijotaSessionTest {

    @TempDir
    Path tempDir;

    private PijotaSession createSession(boolean noSession) throws Exception {
        return createSession(noSession, 0, null);
    }

    private PijotaSession createSession(boolean noSession, int contextWindow,
                                    AppConfig.CompactionConfig compactionConfig) throws Exception {
        var llm = new FauxLlm();
        var tools = new ToolRegistry();
        var sessionManager = new SessionManager(tempDir);
        var extensions = new ExtensionRegistry();
        return new PijotaSession(llm, tools, sessionManager, extensions, "system prompt", tempDir.toString(), noSession,
                                        contextWindow, compactionConfig);
    }

    private static FauxLlm.Response withUsage(String content) {
        return FauxLlm.Response.content(new Usage(1, 1), content);
    }

    @Test
    void constructorCreatesSessionFileByDefault() throws Exception {
        var session = createSession(false);

        assertNotNull(session.sessionFile());
        assertTrue(Files.exists(session.sessionFile()));
    }

    @Test
    void constructorSkipsSessionFileWhenNoSession() throws Exception {
        var session = createSession(true);

        assertNull(session.sessionFile());
    }

    @Test
    void promptRunsAgentAndSavesMessages() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.enqueue(withUsage("hello"));

        var messages = session.prompt("hi").get(2, TimeUnit.SECONDS);

        assertTrue(messages.stream().anyMatch(m -> m instanceof Message.User u && u.content().equals("hi")),
                                        "should contain user message");
        assertTrue(messages.stream().anyMatch(m -> m instanceof Message.Assistant a && a.content().equals("hello")),
                                        "should contain assistant response");
        assertEquals("hello", session.lastAssistantContent());
        assertEquals(1, session.userMessages().size());

        var lines = Files.readAllLines(session.sessionFile());
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"role\":\"user\"")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"role\":\"assistant\"")));
    }

    @Test
    void followUpMessageRunsAdditionalTurn() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.enqueue(withUsage("first")).enqueue(withUsage("second"));

        session.followUp("follow this");
        var messages = session.prompt("hi").get(3, TimeUnit.SECONDS);

        assertTrue(messages.size() >= 5);
        assertEquals("follow this", ((Message.User) messages.get(3)).content());
    }

    @Test
    void abortStopsActivePrompt() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.blockUntilAborted();

        var future = session.prompt("start");
        Thread.sleep(100);
        session.abort();

        assertDoesNotThrow(() -> future.get(1, TimeUnit.SECONDS));
    }

    @Test
    void isBusyReflectsAgentLoopState() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.blockUntilAborted();

        var future = session.prompt("start");
        assertTrue(session.isBusy());
        session.abort();
        future.get(1, TimeUnit.SECONDS);
        assertFalse(session.isBusy());
    }

    @Test
    void sessionNameRoundTrip() throws Exception {
        var session = createSession(false);

        session.setSessionName("My Session");

        assertEquals("My Session", session.sessionName());
    }

    @Test
    void setThinkingLevelPersistsChange() throws Exception {
        var session = createSession(false);

        session.setThinkingLevel(ThinkingLevel.HIGH);

        assertEquals(ThinkingLevel.HIGH, session.latestThinkingLevel());
    }

    @Test
    void switchModelPersistsModelAndCacheMetadata() throws Exception {
        var session = createSession(false);

        session.switchModel("openai", "gpt-4o", "key", 128000);

        var selection = session.latestModelSelection();
        assertNotNull(selection);
        assertEquals("openai", selection.provider());
        assertEquals("gpt-4o", selection.model());

        var cache = session.cacheMetadata();
        assertNotNull(cache);
        assertEquals("openai", cache.provider());
    }

    @Test
    void estimatedContextTokensIsPositiveAfterPrompt() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.enqueue(withUsage("ok"));

        session.prompt("hi").get(2, TimeUnit.SECONDS);

        assertTrue(session.estimatedContextTokens() > 0);
    }

    @Test
    void compactReturnsResult() throws Exception {
        var session = createSession(false, 1000, new AppConfig.CompactionConfig(true, 100, 1));
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.enqueue(withUsage("a1"));
        session.prompt("small").get(2, TimeUnit.SECONDS);
        // Add enough messages so compact can drop some while keeping recent ones
        session.agentLoop().sendUserMessage("old1");
        session.agentLoop().sendUserMessage("old2");
        session.agentLoop().sendUserMessage("recent big " + "x".repeat(100_000));

        var result = session.compact("compact");

        assertTrue(result.compacted());
    }

    @Test
    void saveAndLoadMessages() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.enqueue(withUsage("ok"));
        session.prompt("hi").get(2, TimeUnit.SECONDS);

        var saveFile = tempDir.resolve("saved.jsonl");
        session.saveMessages(saveFile);
        var loadedCount = session.load(saveFile);

        assertTrue(loadedCount >= 2);
    }

    @Test
    void newSessionCreatesAndSwitchesToNewFile() throws Exception {
        var session = createSession(false);
        var original = session.sessionFile();

        var newFile = session.newSession();

        assertNotNull(newFile);
        assertNotEquals(original, newFile);
        assertEquals(newFile, session.sessionFile());
    }

    @Test
    void continueLastSessionLoadsLatestOtherThanCurrent() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.enqueue(withUsage("ok"));
        session.prompt("hi").get(2, TimeUnit.SECONDS); // give it real content so it persists past newSession
        var firstFile = session.sessionFile();
        session.newSession();

        var reloaded = session.continueLastSession();

        assertTrue(reloaded > 0);
        assertEquals(firstFile, session.sessionFile());
    }

    @Test
    void forkFromCreatesNewBranchSession() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.enqueue(withUsage("ok"));
        session.prompt("hi").get(2, TimeUnit.SECONDS);
        var original = session.sessionFile();
        var entryId = session.leafId();
        assertNotNull(entryId);

        var forked = session.forkFrom(entryId);

        assertNotNull(forked);
        assertNotEquals(original, forked);
        assertEquals(forked, session.sessionFile());
    }

    @Test
    void cloneCurrentBranchCreatesNewSession() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.enqueue(withUsage("ok"));
        session.prompt("hi").get(2, TimeUnit.SECONDS);
        var original = session.sessionFile();

        var cloned = session.cloneCurrentBranch();

        assertNotNull(cloned);
        assertNotEquals(original, cloned);
        assertEquals(cloned, session.sessionFile());
    }

    @Test
    void deleteSessionRemovesNonActiveFile() throws Exception {
        var sessionManager = new SessionManager(tempDir);
        var session = createSession(false);
        var other = sessionManager.createSession(tempDir.toString());
        var id = other.getFileName().toString().replace(".jsonl", "");

        session.deleteSession(id);

        assertFalse(Files.exists(other));
    }

    @Test
    void undoLastUserTurnRemovesLastUserAndFollowingMessages() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.enqueue(withUsage("a1")).enqueue(withUsage("a2"));
        session.prompt("first").get(2, TimeUnit.SECONDS);
        session.prompt("second").get(2, TimeUnit.SECONDS);

        var undone = session.undoLastUserTurn();

        assertTrue(undone.isPresent());
        assertEquals("second", undone.get());
    }

    @Test
    void consumeSessionWarningIsEmptyByDefault() {
        assertDoesNotThrow(() -> {
            var session = createSession(true);
            assertTrue(session.consumeSessionWarning().isEmpty());
        });
    }

    @Test
    void closeClosesAgentLoop() {
        assertDoesNotThrow(() -> {
            var session = createSession(true);
            session.close();
            assertTrue(session.agentLoop().isClosed());
        });
    }
}
