package ai.pijota.agent;

import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {
    @TempDir Path tempDir;

    @Test
    void savesAndLoadsConversationMessagesWithoutSystemPrompts() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = tempDir.resolve("session.jsonl");
        var messages = List.<Message>of(
            new Message.System("system prompt"),
            new Message.User("hello"),
            new Message.Assistant("hi", List.of(new ToolCall("tc1", "read", "{}")), "thinking"),
            new Message.ToolResult("tc1", "read", "contents", false)
        );

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
}
