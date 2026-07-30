package org.pijota.llm;

import org.pijota.testkit.FakeHttpClient;
import org.pijota.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

class CodexStreamingChatModelTest {

    /** An SSE stream that yields its preloaded bytes, then blocks on read() until closed — mimics a live socket. */
    private static final class BlockingStream extends InputStream {
        private final byte[] preloaded;
        private int pos;
        private final CountDownLatch closed = new CountDownLatch(1);

        BlockingStream(String preloaded) {
            this.preloaded = preloaded.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            // Return available bytes immediately (like a socket) — don't block to fill the buffer, or the reader would
            // never surface the first line. Once preloaded is drained, block until close(), then signal EOF.
            if (pos < preloaded.length) {
                int n = Math.min(len, preloaded.length - pos);
                System.arraycopy(preloaded, pos, b, off, n);
                pos += n;
                return n;
            }
            try {
                closed.await(); // block until abort() closes us
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return -1; // EOF once closed
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    @Test
    @Timeout(10)
    void abortUnblocksTheReaderAndStopsTheStream() throws Exception {
        // first delta is delivered, then the stream blocks; abort() must close it and end the read promptly
        var stream = new BlockingStream("data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n");
        // a minimal JWT carrying the chatgpt account id so request building succeeds without a live OAuth store
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                                        "{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"acct-test\"}}"
                                                                        .getBytes(StandardCharsets.UTF_8));
        var model = new CodexStreamingChatModel(FakeHttpClient.ok(stream), null, "h." + payload + ".s", "m",
                                        "openai-codex", ThinkingLevel.OFF);

        var gotPartial = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var lastText = new AtomicReference<String>();
        model.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String t) {
                lastText.set(t);
                gotPartial.countDown();
            }

            @Override
            public void onPartialResponse(PartialResponse pr, PartialResponseContext ctx) {
                lastText.set(pr.text()); // the variant the model actually calls
                gotPartial.countDown();
            }

            @Override
            public void onCompleteResponse(ChatResponse r) {
                finished.countDown();
            }

            @Override
            public void onError(Throwable e) {
                finished.countDown();
            }
        });

        assertTrue(gotPartial.await(5, TimeUnit.SECONDS), "the first delta should stream");
        assertEquals("hi", lastText.get());

        model.abort(); // closes the blocking stream → readLine returns → loop ends
        assertTrue(finished.await(5, TimeUnit.SECONDS), "abort must unblock the reader and terminate the stream");
    }

    @Test
    void padBase64AddsOnePaddingChar() {
        assertEquals("abc=", CodexStreamingChatModel.padBase64("abc"));
    }

    @Test
    void padBase64AddsTwoPaddingChars() {
        assertEquals("ab==", CodexStreamingChatModel.padBase64("ab"));
    }

    @Test
    void padBase64AddsNoPaddingForFullBlock() {
        assertEquals("abcd", CodexStreamingChatModel.padBase64("abcd"));
    }

    @Test
    void padBase64HandlesEmptyString() {
        assertEquals("", CodexStreamingChatModel.padBase64(""));
    }

    @Test
    void resolveCodexUrlHandlesNull() {
        assertEquals("https://chatgpt.com/backend-api/codex/responses", CodexStreamingChatModel.resolveCodexUrl(null));
    }

    @Test
    void resolveCodexUrlHandlesBlank() {
        assertEquals("https://chatgpt.com/backend-api/codex/responses", CodexStreamingChatModel.resolveCodexUrl(""));
    }

    @Test
    void resolveCodexUrlAppendsCodexResponses() {
        assertEquals("http://example.com/codex/responses",
                                        CodexStreamingChatModel.resolveCodexUrl("http://example.com"));
    }

    @Test
    void resolveCodexUrlExtendsCodexPath() {
        assertEquals("http://example.com/codex/responses",
                                        CodexStreamingChatModel.resolveCodexUrl("http://example.com/codex"));
    }

    @Test
    void resolveCodexUrlKeepsCodexResponsesPath() {
        assertEquals("http://example.com/codex/responses",
                                        CodexStreamingChatModel.resolveCodexUrl("http://example.com/codex/responses"));
    }

    @Test
    void resolveCodexUrlStripsTrailingSlashes() {
        assertEquals("http://example.com/codex/responses",
                                        CodexStreamingChatModel.resolveCodexUrl("http://example.com///"));
    }

    @Test
    void userAgentContainsPijota() {
        String ua = CodexStreamingChatModel.userAgent();
        assertTrue(ua.startsWith("pijota ("));
        assertTrue(ua.endsWith(")"));
    }

    @Test
    void extractAccountIdParsesValidToken() throws Exception {
        var payload = Util.MAPPER.createObjectNode();
        var auth = Util.MAPPER.createObjectNode();
        auth.put("chatgpt_account_id", "acc-12345");
        payload.set("https://api.openai.com/auth", auth);
        String payloadJson = Util.MAPPER.writeValueAsString(payload);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes());
        String token = "header." + body + ".sig";
        assertEquals("acc-12345", CodexStreamingChatModel.extractAccountId(token));
    }

    @Test
    void extractAccountIdReturnsNullForInvalidToken() {
        assertNull(CodexStreamingChatModel.extractAccountId("not-a-jwt"));
    }

    @Test
    void extractAccountIdReturnsNullForMissingAuth() throws Exception {
        var payload = Util.MAPPER.createObjectNode();
        String payloadJson = Util.MAPPER.writeValueAsString(payload);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes());
        String token = "h." + body + ".s";
        assertNull(CodexStreamingChatModel.extractAccountId(token));
    }

    @Test
    void extractAccountIdReturnsNullForNullAuth() throws Exception {
        var payload = Util.MAPPER.createObjectNode();
        payload.putNull("https://api.openai.com/auth");
        String payloadJson = Util.MAPPER.writeValueAsString(payload);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes());
        String token = "h." + body + ".s";
        assertNull(CodexStreamingChatModel.extractAccountId(token));
    }

    @Test
    void codexThinkingLevelMapsAllLevels() {
        assertEquals("none", CodexStreamingChatModel.codexThinkingLevel(ThinkingLevel.OFF));
        assertEquals("low", CodexStreamingChatModel.codexThinkingLevel(ThinkingLevel.MINIMAL));
        assertEquals("low", CodexStreamingChatModel.codexThinkingLevel(ThinkingLevel.LOW));
        assertEquals("medium", CodexStreamingChatModel.codexThinkingLevel(ThinkingLevel.MEDIUM));
        assertEquals("high", CodexStreamingChatModel.codexThinkingLevel(ThinkingLevel.HIGH));
    }

    @Test
    void codexThinkingLevelDefaultsToNoneWhenNull() {
        assertEquals("none", CodexStreamingChatModel.codexThinkingLevel(null));
    }

    @Test
    void systemPromptReturnsSystemMessage() {
        var msgs = List.<ChatMessage>of(SystemMessage.from("You are a bot"), UserMessage.from("hello"));
        assertEquals("You are a bot", CodexStreamingChatModel.systemPrompt(msgs));
    }

    @Test
    void systemPromptDefaultsWhenNoSystemMessage() {
        var msgs = List.<ChatMessage>of(UserMessage.from("hello"));
        assertEquals("You are a helpful assistant.", CodexStreamingChatModel.systemPrompt(msgs));
    }

    @Test
    void messageItemCreatesUserMessage() {
        var item = CodexStreamingChatModel.messageItem("message", "user", "hello");
        assertEquals("message", item.get("type").asText());
        assertEquals("user", item.get("role").asText());
        assertEquals("input_text", item.get("content").get(0).get("type").asText());
        assertEquals("hello", item.get("content").get(0).get("text").asText());
    }

    @Test
    void messageItemCreatesAssistantMessage() {
        var item = CodexStreamingChatModel.messageItem("message", "assistant", "hi");
        assertEquals("output_text", item.get("content").get(0).get("type").asText());
    }

    @Test
    void toolsConvertsSpecs() {
        var spec = ToolSpecification.builder().name("search").description("Search").build();
        var tools = CodexStreamingChatModel.tools(List.of(spec));
        assertEquals(1, tools.size());
        assertEquals("function", tools.get(0).get("type").asText());
        assertEquals("search", tools.get(0).get("name").asText());
    }

    @Test
    void addToolCallProcessesFunctionCall() throws Exception {
        var item = Util.MAPPER.readTree("{\"type\":\"function_call\",\"call_id\":\"call1\",\"name\":\"search\",\"arguments\":\"{}\"}");
        var toolCalls = new ArrayList<ToolExecutionRequest>();
        var seen = new HashSet<String>();

        CodexStreamingChatModel.addToolCall(item, toolCalls, seen);

        assertEquals(1, toolCalls.size());
        assertEquals("call1", toolCalls.get(0).id());
        assertEquals("search", toolCalls.get(0).name());
    }

    @Test
    void addToolCallSkipsNonFunctionCall() throws Exception {
        var item = Util.MAPPER.readTree("{\"type\":\"message\"}");
        var toolCalls = new ArrayList<ToolExecutionRequest>();
        var seen = new HashSet<String>();

        CodexStreamingChatModel.addToolCall(item, toolCalls, seen);

        assertTrue(toolCalls.isEmpty());
    }

    @Test
    void addToolCallDeduplicatesByCallId() throws Exception {
        var item = Util.MAPPER.readTree("{\"type\":\"function_call\",\"call_id\":\"dup\",\"name\":\"x\",\"arguments\":\"{}\"}");
        var toolCalls = new ArrayList<ToolExecutionRequest>();
        var seen = new HashSet<String>();

        CodexStreamingChatModel.addToolCall(item, toolCalls, seen);
        CodexStreamingChatModel.addToolCall(item, toolCalls, seen);

        assertEquals(1, toolCalls.size());
    }

    @Test
    void collectCompletedProcessesOutputArray() throws Exception {
        var response = Util.MAPPER.readTree(
                                        "{\"output\":[{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"f\",\"arguments\":\"{}\"}]}");
        var toolCalls = new ArrayList<ToolExecutionRequest>();
        var seen = new HashSet<String>();

        CodexStreamingChatModel.collectCompleted(response, toolCalls, seen);

        assertEquals(1, toolCalls.size());
    }

    @Test
    void collectCompletedIgnoresMissingOutput() throws Exception {
        var response = Util.MAPPER.readTree("{}");
        var toolCalls = new ArrayList<ToolExecutionRequest>();
        var seen = new HashSet<String>();

        CodexStreamingChatModel.collectCompleted(response, toolCalls, seen);

        assertTrue(toolCalls.isEmpty());
    }

    @Test
    void inputMessagesConvertsUserAndAi() {
        var msgs = List.<ChatMessage>of(UserMessage.from("hello"), AiMessage.from("response"));
        var result = CodexStreamingChatModel.inputMessages(msgs);
        assertEquals(2, result.size());
        assertEquals("message", result.get(0).get("type").asText());
        assertEquals("user", result.get(0).get("role").asText());
        assertEquals("message", result.get(1).get("type").asText());
        assertEquals("assistant", result.get(1).get("role").asText());
    }

    @Test
    void userMessageSendsImagesAsInputImageNotText() {
        // Regression: an attached image must become an "input_image" content part (data URI), not be stringified into
        // "input_text" — the model can't read raw base64 in a text field (it echoed base64 garbage).
        var user = UserMessage.from(TextContent.from("what is this?"), ImageContent.from("aGVsbG8=", "image/png"));

        var content = CodexStreamingChatModel.userMessageItem(user).get("content");

        assertEquals(2, content.size());
        assertEquals("input_text", content.get(0).get("type").asText());
        assertEquals("what is this?", content.get(0).get("text").asText());
        assertEquals("input_image", content.get(1).get("type").asText());
        assertEquals("data:image/png;base64,aGVsbG8=", content.get(1).get("image_url").asText());
    }

    @Test
    void processSseLineHandlesTextDelta() throws Exception {
        var text = new StringBuilder();
        var toolCalls = new ArrayList<ToolExecutionRequest>();
        var seen = new HashSet<String>();

        String delta = CodexStreamingChatModel.processSseLine(
                                        "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}", text,
                                        toolCalls, seen);

        assertEquals("hello", delta);
        assertEquals("hello", text.toString());
        assertTrue(toolCalls.isEmpty());
    }

    @Test
    void processSseLineHandlesToolCall() throws Exception {
        var text = new StringBuilder();
        var toolCalls = new ArrayList<ToolExecutionRequest>();
        var seen = new HashSet<String>();

        String delta = CodexStreamingChatModel.processSseLine(
                                        "{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"f\",\"arguments\":\"{}\"}}",
                                        text, toolCalls, seen);

        assertNull(delta);
        assertEquals(1, toolCalls.size());
        assertEquals("c1", toolCalls.get(0).id());
    }

    @Test
    void processSseLineHandlesCompleted() throws Exception {
        var text = new StringBuilder();
        var toolCalls = new ArrayList<ToolExecutionRequest>();
        var seen = new HashSet<String>();

        String delta = CodexStreamingChatModel.processSseLine(
                                        "{\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"function_call\",\"call_id\":\"c2\",\"name\":\"g\",\"arguments\":\"{}\"}]}}",
                                        text, toolCalls, seen);

        assertNull(delta);
        assertEquals(1, toolCalls.size());
    }

    @Test
    void processSseLineThrowsOnFailed() {
        var text = new StringBuilder();
        var toolCalls = new ArrayList<ToolExecutionRequest>();
        var seen = new HashSet<String>();

        assertThrows(IOException.class, () -> CodexStreamingChatModel.processSseLine(
                                        "{\"type\":\"response.failed\",\"error\":\"boom\"}", text, toolCalls, seen));
    }

    @Test
    void processSseLineIgnoresUnknownType() throws Exception {
        var text = new StringBuilder();
        var toolCalls = new ArrayList<ToolExecutionRequest>();
        var seen = new HashSet<String>();

        String delta = CodexStreamingChatModel.processSseLine("{\"type\":\"some.other.event\"}", text, toolCalls, seen);

        assertNull(delta);
        assertTrue(toolCalls.isEmpty());
    }

    @Test
    void buildRequestBodySetsModelAndStream() {
        var msgs = List.<ChatMessage>of(UserMessage.from("hello"));
        var request = ChatRequest.builder().messages(msgs).build();

        var body = CodexStreamingChatModel.buildRequestBody(request, "gpt-4", ThinkingLevel.OFF);
        assertEquals("gpt-4", body.get("model").asText());
        assertTrue(body.get("stream").asBoolean());
        assertFalse(body.get("store").asBoolean());
    }

    @Test
    void buildRequestBodyIncludesToolsWhenPresent() {
        var msgs = List.<ChatMessage>of(UserMessage.from("hello"));
        var spec = ToolSpecification.builder().name("search").description("Search").build();
        var request = ChatRequest.builder().messages(msgs).toolSpecifications(List.of(spec)).build();

        var body = CodexStreamingChatModel.buildRequestBody(request, "gpt-4", ThinkingLevel.OFF);
        assertTrue(body.has("tools"));
        assertEquals(1, body.get("tools").size());
        assertEquals("auto", body.get("tool_choice").asText());
    }

    @Test
    void buildRequestBodyAddsReasoningWhenThinkingEnabled() {
        var msgs = List.<ChatMessage>of(UserMessage.from("hello"));
        var request = ChatRequest.builder().messages(msgs).build();

        var body = CodexStreamingChatModel.buildRequestBody(request, "gpt-4", ThinkingLevel.HIGH);
        assertTrue(body.has("reasoning"));
        assertEquals("high", body.get("reasoning").get("effort").asText());
    }

    @Test
    void addToolCallSkipsWhenIdIsBlank() throws Exception {
        var item = Util.MAPPER.readTree("{\"type\":\"function_call\",\"call_id\":\"\",\"name\":\"search\",\"arguments\":\"{}\"}");
        var toolCalls = new ArrayList<ToolExecutionRequest>();
        var seen = new HashSet<String>();

        CodexStreamingChatModel.addToolCall(item, toolCalls, seen);

        assertTrue(toolCalls.isEmpty());
    }

    @Test
    void addToolCallUsesIdFallback() throws Exception {
        var item = Util.MAPPER.readTree("{\"type\":\"function_call\",\"id\":\"alt-id\",\"name\":\"search\",\"arguments\":\"{}\"}");
        var toolCalls = new ArrayList<ToolExecutionRequest>();
        var seen = new HashSet<String>();

        CodexStreamingChatModel.addToolCall(item, toolCalls, seen);

        assertEquals(1, toolCalls.size());
        assertEquals("alt-id", toolCalls.get(0).id());
    }

    @Test
    void toolsHandlesSpecWithoutParameters() {
        var spec = ToolSpecification.builder().name("tool-no-params").description("No params").build();

        var result = CodexStreamingChatModel.tools(List.of(spec));
        assertEquals(1, result.size());
        assertEquals("tool-no-params", result.get(0).get("name").asText());
    }

    @Test
    void inputMessagesSkipsSystemMessages() {
        var msgs = List.<ChatMessage>of(SystemMessage.from("system"));
        var result = CodexStreamingChatModel.inputMessages(msgs);
        assertTrue(result.isEmpty());
    }

    @Test
    void inputMessagesHandlesAiMessageWithOnlyToolCalls() {
        var call = ToolExecutionRequest.builder().id("tc1").name("search").arguments("{}").build();
        var msgs = List.<ChatMessage>of(AiMessage.from(List.of(call)));
        var result = CodexStreamingChatModel.inputMessages(msgs);
        assertEquals(1, result.size());
        assertEquals("function_call", result.get(0).get("type").asText());
        assertEquals("tc1", result.get(0).get("call_id").asText());
    }
}
