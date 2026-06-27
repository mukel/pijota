package ai.pijota.llm;

import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;

import java.util.List;
import java.util.Set;

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
    void userImageBecomesMultipartUserMessageWhenModalityAllowed() {
        var user = new Message.User("what is this?", List.of(new Message.Attachment("image/png", "QQ==", "x.png")));
        var result = MessageConverter.toLangChain4j(List.<Message>of(user), java.util.Set.of(Modality.IMAGE));
        var um = (dev.langchain4j.data.message.UserMessage) result.get(0);
        assertEquals(2, um.contents().size(), "text part + image part");
        assertTrue(um.contents().get(0) instanceof dev.langchain4j.data.message.TextContent);
        assertTrue(um.contents().get(1) instanceof dev.langchain4j.data.message.ImageContent);
    }

    @Test
    void pdfBecomesPdfFileContentWhenAllowed() {
        var user = new Message.User("read this", List.of(new Message.Attachment("application/pdf", "QQ==", "d.pdf")));
        var result = MessageConverter.toLangChain4j(List.<Message>of(user), java.util.Set.of(Modality.PDF));
        var um = (dev.langchain4j.data.message.UserMessage) result.get(0);
        assertTrue(um.contents().stream().anyMatch(c -> c instanceof dev.langchain4j.data.message.PdfFileContent));
    }

    @Test
    void mediaDroppedWithNoteWhenModalityNotAllowed() {
        var user = new Message.User("what is this?", List.of(new Message.Attachment("image/png", "QQ==", "x.png")));
        var result = MessageConverter.toLangChain4j(List.<Message>of(user), java.util.Set.of(Modality.TEXT));
        var um = (dev.langchain4j.data.message.UserMessage) result.get(0);
        assertTrue(um.contents().stream().noneMatch(c -> c instanceof dev.langchain4j.data.message.ImageContent),
                                        "no image sent when the model doesn't accept images");
        assertTrue(um.singleText().contains("omitted"), um.singleText());
    }

    @Test
    void audioAndVideoBecomeTypedContentWhenAllowed() {
        var user = new Message.User("media", List.of(new Message.Attachment("audio/mpeg", "QQ==", "a.mp3"),
                                        new Message.Attachment("video/mp4", "QQ==", "v.mp4")));
        var um = (UserMessage) MessageConverter.toLangChain4j(List.<Message>of(user),
                                        Set.of(Modality.AUDIO, Modality.VIDEO)).get(0);
        assertTrue(um.contents().stream().anyMatch(c -> c instanceof AudioContent));
        assertTrue(um.contents().stream().anyMatch(c -> c instanceof VideoContent));
    }

    @Test
    void multipleDroppedModalitiesProduceAPluralNote() {
        var user = new Message.User("look", List.of(new Message.Attachment("image/png", "QQ==", "1.png"),
                                        new Message.Attachment("application/pdf", "QQ==", "a.pdf"),
                                        new Message.Attachment("application/pdf", "QQ==", "b.pdf")));
        var um = (UserMessage) MessageConverter.toLangChain4j(List.<Message>of(user), Set.of(Modality.TEXT)).get(0);
        String t = um.singleText();
        assertTrue(t.contains("1 image"), t);
        assertTrue(t.contains("2 pdfs"), t);
        assertTrue(t.contains("omitted"), t);
    }

    @Test
    void allowedModalityKeptWhileDisallowedDroppedWithNote() {
        var user = new Message.User("mix", List.of(new Message.Attachment("image/png", "QQ==", "k.png"),
                                        new Message.Attachment("application/pdf", "QQ==", "d.pdf")));
        var um = (UserMessage) MessageConverter.toLangChain4j(List.<Message>of(user), Set.of(Modality.IMAGE)).get(0);
        assertTrue(um.contents().stream().anyMatch(c -> c instanceof ImageContent), "image kept");
        assertTrue(um.contents().stream().noneMatch(c -> c instanceof PdfFileContent), "pdf dropped");
        assertTrue(um.contents().stream().filter(c -> c instanceof TextContent).map(c -> ((TextContent) c).text())
                                        .anyMatch(s -> s.contains("pdf") && s.contains("omitted")));
    }

    @Test
    void nullAllowedKeepsEveryModality() {
        var user = new Message.User("all", List.of(new Message.Attachment("image/png", "QQ==", "i.png"),
                                        new Message.Attachment("application/pdf", "QQ==", "d.pdf")));
        var um = (UserMessage) MessageConverter.toLangChain4j(List.<Message>of(user), null).get(0); // null = allow all
        assertTrue(um.contents().stream().anyMatch(c -> c instanceof ImageContent));
        assertTrue(um.contents().stream().anyMatch(c -> c instanceof PdfFileContent));
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
