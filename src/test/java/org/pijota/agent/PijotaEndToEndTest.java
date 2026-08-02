package org.pijota.agent;

import org.pijota.messages.Message;
import org.pijota.messages.ToolCall;
import org.pijota.messages.Usage;
import org.pijota.testkit.FauxLlm;
import org.pijota.tools.Tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.pijota.testkit.FauxLlm.Response.contentWithUsage;

class PijotaEndToEndTest {

    @TempDir
    Path tempDir;

    private PijotaSession createSession(boolean noSession) {
        var builder = TestSession.in(tempDir);
        if (noSession)
            builder.noSession();
        return builder.build().session();
    }

    @Test
    void fullSessionLifecyclePromptExportLoadDelete() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        session.setSessionName("Lifecycle Test");

        llm.enqueue(contentWithUsage("hello from AI")).enqueue(contentWithUsage("second response"));
        session.prompt("first question").get(2, TimeUnit.SECONDS);
        session.prompt("second question").get(2, TimeUnit.SECONDS);

        var saveFile = tempDir.resolve("lifecycle-save.jsonl");
        session.saveMessages(saveFile);
        assertTrue(Files.exists(saveFile));

        var markdown = SessionExporter.toMarkdown(session.sessionFile());
        assertTrue(markdown.contains("first question"), "export should contain question");
        assertTrue(markdown.contains("second question"), "export should contain question");

        var freshSession = createSession(false);
        var loadedCount = freshSession.load(saveFile);
        assertTrue(loadedCount >= 3, "loaded " + loadedCount + " messages");
        var messages = freshSession.messages();
        assertTrue(messages.stream().anyMatch(
                                        m -> m instanceof Message.User u && u.content().equals("first question")));
        assertTrue(messages.stream().anyMatch(
                                        m -> m instanceof Message.Assistant a && a.content().equals("hello from AI")));
    }

    @Test
    void toolCallCycleExecutesAndResumesConversation() throws Exception {
        var session = createSession(false);
        var toolRegistry = session.agentLoop().toolRegistry();
        toolRegistry.register(echoTool());
        var llm = (FauxLlm) session.agentLoop().llm();

        llm.enqueue(FauxLlm.Response.toolCall(new ToolCall("tc1", "echo", "{\"text\":\"tool output\"}")))
                                        .enqueue(FauxLlm.Response.content(new Usage(1, 2), "processed tool result"));
        session.prompt("call echo").get(2, TimeUnit.SECONDS);

        var messages = session.messages();
        assertTrue(messages.size() >= 4, "expected at least 4 messages, got " + messages.size());
        assertTrue(messages.stream().anyMatch(m -> m instanceof Message.ToolResult tr
                                        && tr.content().contains("tool output")), "should contain tool result");
        assertTrue(messages.stream().anyMatch(m -> m instanceof Message.Assistant a
                                        && a.content().equals("processed tool result")),
                                        "should contain assistant response after tool");

        llm.enqueue(contentWithUsage("follow-up answer"));
        session.prompt("follow up").get(2, TimeUnit.SECONDS);
        assertEquals(2, session.userMessages().size());
        assertEquals("follow up", session.userMessages().get(1).content());
    }

    @Test
    void multipleTurnsWithSaveAndReloadPreservesAllMessages() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.enqueue(contentWithUsage("a1"));
        session.prompt("q1").get(2, TimeUnit.SECONDS);
        llm.enqueue(contentWithUsage("a2"));
        session.prompt("q2").get(2, TimeUnit.SECONDS);
        llm.enqueue(contentWithUsage("a3"));
        session.prompt("q3").get(2, TimeUnit.SECONDS);

        assertEquals(3, session.userMessages().size());
        assertTrue(session.estimatedContextTokens() > 0);
        assertEquals("a3", session.lastAssistantContent());

        var saveFile = tempDir.resolve("multi-turn.jsonl");
        session.saveMessages(saveFile);
        var reloaded = createSession(false);
        int loaded = reloaded.load(saveFile);

        assertTrue(loaded >= 5, "loaded " + loaded + " messages");
        assertEquals(3, reloaded.userMessages().size());
        assertEquals("a3", reloaded.lastAssistantContent());
    }

    @Test
    void undoChainRemovesMessagesSequentially() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();

        llm.enqueue(contentWithUsage("a1"));
        session.prompt("keep").get(2, TimeUnit.SECONDS);
        llm.enqueue(contentWithUsage("a2"));
        session.prompt("middle").get(2, TimeUnit.SECONDS);
        llm.enqueue(contentWithUsage("a3"));
        session.prompt("last").get(2, TimeUnit.SECONDS);

        assertTrue(session.messages().size() >= 6);

        var firstUndo = session.undoLastUserTurn();
        assertTrue(firstUndo.isPresent());
        assertEquals("last", firstUndo.get());

        var secondUndo = session.undoLastUserTurn();
        assertTrue(secondUndo.isPresent());
        assertEquals("middle", secondUndo.get());

        var remaining = session.messages();
        assertTrue(remaining.size() >= 2);
        assertEquals("keep", ((Message.User) remaining.get(1)).content());
    }

    @Test
    void sessionNamingPersistsInSessionFile() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        session.setSessionName("Named Session");
        llm.enqueue(contentWithUsage("ok"));
        session.prompt("hi").get(2, TimeUnit.SECONDS);

        var file = session.sessionFile();
        assertNotNull(file);
        var content = Files.readString(file);
        assertTrue(content.contains("Named Session"));
    }

    @Test
    void newSessionClearsMessagesAndStartsClean() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.enqueue(contentWithUsage("old answer"));
        session.prompt("old question").get(2, TimeUnit.SECONDS);

        int beforeCount = session.messages().size();
        assertTrue(beforeCount > 0);
        session.newSession();

        assertTrue(session.userMessages().isEmpty(), "user messages should be empty after new session");

        llm.enqueue(contentWithUsage("new answer"));
        session.prompt("new question").get(2, TimeUnit.SECONDS);

        assertEquals(1, session.userMessages().size());
        assertEquals("new question", session.userMessages().get(0).content());
    }

    @Test
    void errorRetrySendsExistingMessagesToNextAttempt() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.enqueue(FauxLlm.Response.content(new Usage(1, 1), "a1"));
        session.prompt("q1").get(2, TimeUnit.SECONDS);

        llm.enqueueError("temporary server error").enqueue(contentWithUsage("recovery"));
        session.prompt("q2").get(5, TimeUnit.SECONDS);

        assertEquals(2, session.userMessages().size());
        assertEquals("recovery", session.lastAssistantContent());
    }

    @Test
    void contextTokensIncreaseAfterPrompting() throws Exception {
        var session = createSession(false);
        long initialTokens = session.estimatedContextTokens();

        var llm = (FauxLlm) session.agentLoop().llm();
        // Realistic prompt-token usage (not the nominal Usage(1,1)): the context anchor keys off the provider's
        // reported
        // promptTokens, and a degenerate 1 would drive the overhead negative and collapse the count to its floor.
        llm.enqueue(FauxLlm.Response.content(new Usage(1000, 50), "hello world " + "x".repeat(500)));
        session.prompt("test question " + "y".repeat(500)).get(2, TimeUnit.SECONDS);

        long afterTokens = session.estimatedContextTokens();
        assertTrue(afterTokens > 0);
        assertTrue(afterTokens > initialTokens, "tokens should increase: " + initialTokens + " → " + afterTokens);
    }

    @Test
    void switchModelPersistsAndIsReflectedInCache() throws Exception {
        var session = createSession(false);
        session.switchModel("anthropic", "claude-sonnet", "key-123", 200_000);

        var selection = session.latestModelSelection();
        assertEquals("anthropic", selection.provider());
        assertEquals("claude-sonnet", selection.model());

        var cache = session.cacheMetadata();
        assertEquals("anthropic", cache.provider());
    }

    @Test
    void listSessionsReturnsKnownSessions() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.enqueue(contentWithUsage("ok"));
        session.prompt("test").get(2, TimeUnit.SECONDS);
        session.setSessionName("EndToEnd Session");

        var sessions = session.listSessions();
        assertFalse(sessions.isEmpty(), "should list at least one session");
        assertTrue(sessions.stream().anyMatch(p -> p.equals(session.sessionFile())),
                                        "should find current session in list");
    }

    @Test
    void exportSessionProducesMarkdown() throws Exception {
        var session = createSession(false);
        var llm = (FauxLlm) session.agentLoop().llm();
        llm.enqueue(contentWithUsage("AI response"));
        session.prompt("test question").get(2, TimeUnit.SECONDS);

        var markdown = SessionExporter.toMarkdown(session.sessionFile());
        assertNotNull(markdown);
        assertTrue(markdown.contains("test question"), "export should contain user question");
        assertTrue(markdown.contains("AI response"), "export should contain assistant response");
    }

    private static Tool echoTool() {
        return new Tool.ToolDefinition("echo", "Echoes input text", Map.of(),
                                        (id, args, cwd) -> CompletableFuture.completedFuture(Tool.ToolResult.success(
                                                                        "Echo: " + args.getOrDefault("text", ""))))
                                        .toTool();
    }
}
