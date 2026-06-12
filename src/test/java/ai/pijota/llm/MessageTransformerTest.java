package ai.pijota.llm;

import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageTransformerTest {

    @Test
    void transformEmptyReturnsEmpty() {
        var result = MessageTransformer.transform(List.of(), "openai", "gpt-4");
        assertTrue(result.isEmpty());
    }

    @Test
    void transformNullReturnsEmpty() {
        var result = MessageTransformer.transform(null, "openai", "gpt-4");
        assertTrue(result.isEmpty());
    }

    @Test
    void sameProviderPassesThrough() {
        var msgs = List.<Message>of(new Message.User("hello"), new Message.Assistant("hi", List.of(), ""));
        var result = MessageTransformer.transform(msgs, "same", "same");
        assertEquals(2, result.size());
        assertEquals("hello", ((Message.User) result.get(0)).content());
        assertEquals("hi", ((Message.Assistant) result.get(1)).content());
    }

    @Test
    void assistantWithSourceSameAsTargetKeepsThinking() {
        var assistant = new Message.Assistant("text", List.of(), "thinking");
        var m = (Message.Assistant) new Message.Assistant(assistant.id(), assistant.timestamp(), assistant.content(),
                                        assistant.toolCalls(), assistant.thinking(), "openai", "gpt-4");
        var msgs = List.<Message>of(m);
        var result = MessageTransformer.transform(msgs, "openai", "gpt-4");
        var transformed = (Message.Assistant) result.get(0);
        assertEquals("thinking", transformed.thinking());
    }

    @Test
    void differentProviderStripsThinking() {
        var assistant = new Message.Assistant("text", List.of(), "thinking");
        var m = (Message.Assistant) new Message.Assistant(assistant.id(), assistant.timestamp(), assistant.content(),
                                        assistant.toolCalls(), assistant.thinking(), "old-provider", "old-model");
        var msgs = List.<Message>of(m);
        var result = MessageTransformer.transform(msgs, "new-provider", "new-model");
        var transformed = (Message.Assistant) result.get(0);
        assertEquals("", transformed.thinking());
        assertEquals("old-provider", transformed.sourceProvider());
        assertEquals("old-model", transformed.sourceModel());
    }

    @Test
    void userAndSystemPassThroughUnchanged() {
        var msgs = List.<Message>of(new Message.System("sys"), new Message.User("user"));
        var result = MessageTransformer.transform(msgs, "any", "any");
        assertEquals(2, result.size());
        assertTrue(result.get(0) instanceof Message.System);
        assertTrue(result.get(1) instanceof Message.User);
    }

    @Test
    void assistantWithToolCallsNormalizesIds() {
        var calls = List.of(new ToolCall("long call\nid with spaces", "read", "{}"));
        var m = new Message.Assistant("text", calls, null);
        var assistant = (Message.Assistant) new Message.Assistant(m.id(), m.timestamp(), m.content(), m.toolCalls(),
                                        m.thinking(), "old-p", "old-m");
        var msgs = List.<Message>of(assistant);
        var result = MessageTransformer.transform(msgs, "new-p", "new-m");
        var transformed = (Message.Assistant) result.get(0);
        assertEquals(1, transformed.toolCalls().size());
        var normalizedId = transformed.toolCalls().get(0).id();
        assertFalse(normalizedId.contains(" "));
    }

    @Test
    void toolResultsReferencingNormalizedIdsGetRemapped() {
        var originalCallId = "my-long-tool-call-id-with-special!chars";
        var calls = List.of(new ToolCall(originalCallId, "read", "{}"));
        var m = new Message.Assistant("text", calls, null);
        var assistant = (Message.Assistant) new Message.Assistant(m.id(), m.timestamp(), m.content(), m.toolCalls(),
                                        m.thinking(), "old-p", "old-m");
        var tr = new Message.ToolResult(originalCallId, "read", "output", false);
        var msgs = List.<Message>of(assistant, tr);
        var result = MessageTransformer.transform(msgs, "new-p", "new-m");

        var transformedAssistant = (Message.Assistant) result.get(0);
        var normalizedId = transformedAssistant.toolCalls().get(0).id();
        var transformedTr = (Message.ToolResult) result.get(1);
        assertEquals(normalizedId, transformedTr.toolCallId());
    }

    @Test
    void sameSourcePreservesCallIds() {
        var calls = List.of(new ToolCall("call-1", "read", "{}"));
        var m = new Message.Assistant("text", calls, null);
        var assistant = (Message.Assistant) new Message.Assistant(m.id(), m.timestamp(), m.content(), m.toolCalls(),
                                        m.thinking(), "openai", "gpt-4");
        var tr = new Message.ToolResult("call-1", "read", "out", false);
        var msgs = List.<Message>of(assistant, tr);
        var result = MessageTransformer.transform(msgs, "openai", "gpt-4");
        var transformedTr = (Message.ToolResult) result.get(1);
        assertEquals("call-1", transformedTr.toolCallId());
    }

    @Test
    void compactionSummaryPassesThrough() {
        var msgs = List.<Message>of(new Message.CompactionSummary("summary"));
        var result = MessageTransformer.transform(msgs, "any", "any");
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof Message.CompactionSummary);
    }

    @Test
    void branchSummaryPassesThrough() {
        var msgs = List.<Message>of(new Message.BranchSummary("branch"));
        var result = MessageTransformer.transform(msgs, "any", "any");
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof Message.BranchSummary);
    }

    @Test
    void assistantWithoutToolCallsStripsThinkingWhenProviderDiffers() {
        var m = new Message.Assistant("text", List.of(), "think");
        var assistant = (Message.Assistant) new Message.Assistant(m.id(), m.timestamp(), m.content(), m.toolCalls(),
                                        m.thinking(), "old-p", "old-m");
        var msgs = List.<Message>of(assistant);
        var result = MessageTransformer.transform(msgs, "new-p", "new-m");
        var transformed = (Message.Assistant) result.get(0);
        assertEquals("", transformed.thinking());
    }

    @Test
    void repairToolSequencesAddsMissingResults() {
        var calls = List.of(new ToolCall("c1", "read", "{}"), new ToolCall("c2", "glob", "{}"));
        var m = new Message.Assistant("text", calls, null);
        var assistant = (Message.Assistant) new Message.Assistant(m.id(), m.timestamp(), m.content(), m.toolCalls(),
                                        m.thinking(), "anthropic", "claude");
        var tr = new Message.ToolResult("c1", "read", "out", false);
        var msgs = List.<Message>of(assistant, tr);
        var result = MessageTransformer.transform(msgs, "anthropic", "claude");
        assertTrue(result.size() >= 3);
        var last = result.get(result.size() - 1);
        assertTrue(last instanceof Message.ToolResult);
        var lastTr = (Message.ToolResult) last;
        assertEquals("c2", lastTr.toolCallId());
        assertTrue(lastTr.isError());
    }

    @Test
    void repairToolSequencesAllResultsPresentDoesNotAdd() {
        var calls = List.of(new ToolCall("c1", "read", "{}"));
        var m = new Message.Assistant("text", calls, null);
        var assistant = (Message.Assistant) new Message.Assistant(m.id(), m.timestamp(), m.content(), m.toolCalls(),
                                        m.thinking(), "anthropic", "claude");
        var tr = new Message.ToolResult("c1", "read", "out", false);
        var msgs = List.<Message>of(assistant, tr);
        var result = MessageTransformer.transform(msgs, "anthropic", "claude");
        assertEquals(2, result.size());
    }

    @Test
    void repairToolSequencesNoToolCallsDoesNotAdd() {
        var msgs = List.<Message>of(new Message.User("hi"), new Message.Assistant("hello", List.of(), ""));
        var result = MessageTransformer.transform(msgs, "any", "any");
        assertEquals(2, result.size());
    }

    @Test
    void toolResultWithoutMappedIdPassesThrough() {
        var calls = List.of(new ToolCall("unique-call-id", "read", "{}"));
        var m = new Message.Assistant("text", calls, null);
        var assistant = (Message.Assistant) new Message.Assistant(m.id(), m.timestamp(), m.content(), m.toolCalls(),
                                        m.thinking(), "src-p", "src-m");
        var tr = new Message.ToolResult("unique-call-id", "read", "out", false);
        var msgs = List.<Message>of(assistant, tr);
        var result = MessageTransformer.transform(msgs, "dst-p", "dst-m");
        var transformedTr = (Message.ToolResult) result.get(1);
        assertFalse(transformedTr.toolCallId().contains("!"));
    }

    @Test
    void resultIsImmutableListCopy() {
        var msgs = List.<Message>of(new Message.User("hi"));
        var result = MessageTransformer.transform(msgs, "any", "any");
        assertThrows(UnsupportedOperationException.class, () -> result.add(new Message.User("cannot add")));
    }
}
