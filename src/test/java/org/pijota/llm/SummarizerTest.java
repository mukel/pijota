package org.pijota.llm;

import org.pijota.messages.Message;
import org.pijota.messages.ToolCall;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SummarizerTest {

    @Test
    void buildSummaryPromptWithNoPreviousSummary() {
        var msgs = List.<Message>of(new Message.User("hello"), new Message.User("world"));
        var prompt = Summarizer.buildSummaryPrompt(msgs, null, null);
        assertTrue(prompt.contains("<conversation>"));
        assertTrue(prompt.contains("hello"));
        assertTrue(prompt.contains("world"));
        assertTrue(prompt.contains("Create a structured context checkpoint"));
    }

    @Test
    void buildSummaryPromptWithPreviousSummary() {
        var msgs = List.<Message>of(new Message.User("new message"));
        var prompt = Summarizer.buildSummaryPrompt(msgs, null, "old context");
        assertTrue(prompt.contains("<previous-summary>"));
        assertTrue(prompt.contains("old context"));
        assertTrue(prompt.contains("Update the existing structured summary"));
    }

    @Test
    void buildSummaryPromptWithInstructions() {
        var msgs = List.<Message>of(new Message.User("task"));
        var prompt = Summarizer.buildSummaryPrompt(msgs, "focus on errors", null);
        assertTrue(prompt.contains("Additional focus: focus on errors"));
    }

    @Test
    void buildSummaryPromptBlankInstructionsOmitted() {
        var msgs = List.<Message>of(new Message.User("task"));
        var prompt = Summarizer.buildSummaryPrompt(msgs, "  ", null);
        assertFalse(prompt.contains("Additional focus"));
    }

    @Test
    void buildSummaryPromptIncludesAssistantContent() {
        var msgs = List.<Message>of(new Message.Assistant("I'll help", List.of(), ""));
        var prompt = Summarizer.buildSummaryPrompt(msgs, null, null);
        assertTrue(prompt.contains("[Assistant]: I'll help"));
    }

    @Test
    void buildSummaryPromptIncludesThinking() {
        var msgs = List.<Message>of(new Message.Assistant("answer", List.of(), "Hmm, let me think"));
        var prompt = Summarizer.buildSummaryPrompt(msgs, null, null);
        assertTrue(prompt.contains("[Assistant thinking]:"));
        assertTrue(prompt.contains("Hmm, let me think"));
    }

    @Test
    void buildSummaryPromptSkipsBlankThinking() {
        var msgs = List.<Message>of(new Message.Assistant("answer", List.of(), "  "));
        var prompt = Summarizer.buildSummaryPrompt(msgs, null, null);
        assertFalse(prompt.contains("[Assistant thinking]"));
    }

    @Test
    void buildSummaryPromptIncludesToolCalls() {
        var calls = List.of(new ToolCall("c1", "read", "{\"file\":\"x.txt\"}"));
        var msgs = List.<Message>of(new Message.Assistant("", calls, ""));
        var prompt = Summarizer.buildSummaryPrompt(msgs, null, null);
        assertTrue(prompt.contains("[Assistant tool calls]:"));
        assertTrue(prompt.contains("read("));
        assertTrue(prompt.contains("x.txt"));
    }

    @Test
    void buildSummaryPromptIncludesToolResults() {
        var msgs = List.<Message>of(new Message.ToolResult("c1", "read", "file content", false));
        var prompt = Summarizer.buildSummaryPrompt(msgs, null, null);
        assertTrue(prompt.contains("[Tool result: read]"));
        assertTrue(prompt.contains("file content"));
    }

    @Test
    void buildSummaryPromptMarksErrorToolResults() {
        var msgs = List.<Message>of(new Message.ToolResult("c1", "bash", "command failed", true));
        var prompt = Summarizer.buildSummaryPrompt(msgs, null, null);
        assertTrue(prompt.contains("[Tool result: bash error]"));
    }

    @Test
    void buildSummaryPromptIncludesCompactionSummary() {
        var msgs = List.<Message>of(new Message.CompactionSummary("previous context"));
        var prompt = Summarizer.buildSummaryPrompt(msgs, null, null);
        assertTrue(prompt.contains("[Previous conversation summary]"));
        assertTrue(prompt.contains("previous context"));
    }

    @Test
    void buildSummaryPromptEmptyMessagesStillProducesPrompt() {
        var msgs = List.<Message>of();
        var prompt = Summarizer.buildSummaryPrompt(msgs, null, null);
        assertTrue(prompt.contains("## Goal"));
        assertTrue(prompt.contains("## Constraints & Preferences"));
        assertTrue(prompt.contains("## Progress"));
        assertTrue(prompt.contains("## Key Decisions"));
        assertTrue(prompt.contains("## Next Steps"));
        assertTrue(prompt.contains("## Critical Context"));
    }

    @Test
    void buildSummaryPromptBlankInstructions() {
        var msgs = List.<Message>of(new Message.User("hi"));
        var prompt = Summarizer.buildSummaryPrompt(msgs, "", null);
        assertFalse(prompt.contains("Additional focus"));
    }
}
