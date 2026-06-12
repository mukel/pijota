package ai.pijota.llm;

import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageConverterTest {

    @Test
    void convertsUserMessage() {
        var msgs = List.<Message>of(new Message.User("hello"));
        var result = MessageConverter.toLangChain4j(msgs);
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof dev.langchain4j.data.message.UserMessage);
        assertEquals("hello", ((dev.langchain4j.data.message.UserMessage) result.get(0)).singleText());
    }

    @Test
    void convertsSystemMessage() {
        var msgs = List.<Message>of(new Message.System("you are a bot"));
        var result = MessageConverter.toLangChain4j(msgs);
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof dev.langchain4j.data.message.SystemMessage);
        assertEquals("you are a bot", ((dev.langchain4j.data.message.SystemMessage) result.get(0)).text());
    }

    @Test
    void convertsAssistantMessageWithoutToolCalls() {
        var msgs = List.<Message>of(new Message.Assistant("response", List.of(), ""));
        var result = MessageConverter.toLangChain4j(msgs);
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof dev.langchain4j.data.message.AiMessage);
        assertEquals("response", ((dev.langchain4j.data.message.AiMessage) result.get(0)).text());
    }

    @Test
    void convertsAssistantWithThinking() {
        var msgs = List.<Message>of(new Message.Assistant("response", List.of(), "thinking..."));
        var result = MessageConverter.toLangChain4j(msgs);
        assertEquals(1, result.size());
        var aiMsg = (dev.langchain4j.data.message.AiMessage) result.get(0);
        assertEquals("thinking...", aiMsg.thinking());
    }

    @Test
    void convertsAssistantWithToolCallsAndImmediateResults() {
        var calls = List.of(new ToolCall("call-1", "read", "{\"file\":\"x.txt\"}"));
        var msgs = List.<Message>of(new Message.Assistant("", calls, ""),
                                        new Message.ToolResult("call-1", "read", "contents", false));
        var result = MessageConverter.toLangChain4j(msgs);
        assertEquals(2, result.size());
        var aiMsg = (dev.langchain4j.data.message.AiMessage) result.get(0);
        assertFalse(aiMsg.toolExecutionRequests().isEmpty());
        assertEquals("call-1", aiMsg.toolExecutionRequests().get(0).id());
        assertTrue(result.get(1) instanceof dev.langchain4j.data.message.ToolExecutionResultMessage);
    }

    @Test
    void assistantWithToolCallsButMissingResultsOmitsBoth() {
        var calls = List.of(new ToolCall("call-1", "read", "{}"));
        var msgs = List.<Message>of(new Message.Assistant("text", calls, ""));
        var result = MessageConverter.toLangChain4j(msgs);
        assertEquals(1, result.size());
        var aiMsg = (dev.langchain4j.data.message.AiMessage) result.get(0);
        assertTrue(aiMsg.toolExecutionRequests().isEmpty());
    }

    @Test
    void convertsToolResult() {
        var calls = List.of(new ToolCall("call-1", "read", "{}"));
        var msgs = List.<Message>of(new Message.Assistant("", calls, ""),
                                        new Message.ToolResult("call-1", "read", "content", false));
        var result = MessageConverter.toLangChain4j(msgs);
        var toolMsg = (dev.langchain4j.data.message.ToolExecutionResultMessage) result.get(1);
        assertEquals("call-1", toolMsg.id());
        assertEquals("read", toolMsg.toolName());
        assertEquals("content", toolMsg.text());
        assertNull(toolMsg.isError());
    }

    @Test
    void convertsToolResultWithError() {
        var calls = List.of(new ToolCall("call-1", "bash", "{}"));
        var msgs = List.<Message>of(new Message.Assistant("", calls, ""),
                                        new Message.ToolResult("call-1", "bash", "failed", true));
        var result = MessageConverter.toLangChain4j(msgs);
        var toolMsg = (dev.langchain4j.data.message.ToolExecutionResultMessage) result.get(1);
        assertEquals(Boolean.TRUE, toolMsg.isError());
    }

    @Test
    void convertsCompactionSummary() {
        var msgs = List.<Message>of(new Message.CompactionSummary("summarized"));
        var result = MessageConverter.toLangChain4j(msgs);
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof dev.langchain4j.data.message.UserMessage);
        assertTrue(((dev.langchain4j.data.message.UserMessage) result.get(0)).singleText().contains("summarized"));
    }

    @Test
    void convertsBranchSummary() {
        var msgs = List.<Message>of(new Message.BranchSummary("branch context"));
        var result = MessageConverter.toLangChain4j(msgs);
        assertEquals(1, result.size());
        var text = ((dev.langchain4j.data.message.UserMessage) result.get(0)).singleText();
        assertTrue(text.contains("Branch context"));
        assertTrue(text.contains("branch context"));
    }

    @Test
    void skipsCustomMessages() {
        var msgs = List.<Message>of(new Message.Custom("type", "content"));
        var result = MessageConverter.toLangChain4j(msgs);
        assertTrue(result.isEmpty());
    }

    @Test
    void multipleToolCallsWithCompleteResults() {
        var calls = List.of(new ToolCall("c1", "read", "{}"), new ToolCall("c2", "glob", "{}"));
        var msgs = List.<Message>of(new Message.Assistant("", calls, ""),
                                        new Message.ToolResult("c1", "read", "file", false),
                                        new Message.ToolResult("c2", "glob", "found", false));
        var result = MessageConverter.toLangChain4j(msgs);
        assertEquals(3, result.size());
        var aiMsg = (dev.langchain4j.data.message.AiMessage) result.get(0);
        assertEquals(2, aiMsg.toolExecutionRequests().size());
    }

    @Test
    void partialToolResultsDoNotConvertToolCalls() {
        var calls = List.of(new ToolCall("c1", "read", "{}"), new ToolCall("c2", "glob", "{}"));
        var msgs = List.<Message>of(new Message.Assistant("I'll read this", calls, ""),
                                        new Message.ToolResult("c1", "read", "found", false));
        var result = MessageConverter.toLangChain4j(msgs);
        assertEquals(1, result.size());
        var aiMsg = (dev.langchain4j.data.message.AiMessage) result.get(0);
        assertTrue(aiMsg.toolExecutionRequests().isEmpty());
    }

    @Test
    void assistantWithoutContentAndThinkingWhenNoToolCallsStillAdded() {
        var msgs = List.<Message>of(new Message.Assistant("", List.of(), ""));
        var result = MessageConverter.toLangChain4j(msgs);
        assertEquals(1, result.size());
    }

    @Test
    void mixedConversationFlow() {
        var calls = List.of(new ToolCall("c1", "read", "{}"));
        var msgs = List.<Message>of(new Message.User("read file.txt"), new Message.Assistant("", calls, ""),
                                        new Message.ToolResult("c1", "read", "file content", false),
                                        new Message.Assistant("Here is the content", List.of(), ""));
        var result = MessageConverter.toLangChain4j(msgs);

        assertTrue(result.get(0) instanceof dev.langchain4j.data.message.UserMessage);
        assertTrue(result.get(1) instanceof dev.langchain4j.data.message.AiMessage);
        assertTrue(result.get(2) instanceof dev.langchain4j.data.message.ToolExecutionResultMessage);
        assertTrue(result.get(3) instanceof dev.langchain4j.data.message.AiMessage);
    }

    @Test
    void nullAssistantContentTreatedAsEmpty() {
        var msgs = List.<Message>of(new Message.Assistant(null, List.of(), ""));
        var result = MessageConverter.toLangChain4j(msgs);
        var aiMsg = (dev.langchain4j.data.message.AiMessage) result.get(0);
        assertEquals("", aiMsg.text());
    }
}
