package ai.pijota.llm;

import ai.pijota.util.Util;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalOpenAiStreamingChatModelTest {

    @Test
    void normalizeBaseUrlHandlesNull() {
        assertEquals("http://localhost:17325/v1", LocalOpenAiStreamingChatModel.normalizeBaseUrl(null));
    }

    @Test
    void normalizeBaseUrlHandlesBlank() {
        assertEquals("http://localhost:17325/v1", LocalOpenAiStreamingChatModel.normalizeBaseUrl(""));
        assertEquals("http://localhost:17325/v1", LocalOpenAiStreamingChatModel.normalizeBaseUrl("  "));
    }

    @Test
    void normalizeBaseUrlStripsTrailingSlashes() {
        assertEquals("http://example.com", LocalOpenAiStreamingChatModel.normalizeBaseUrl("http://example.com///"));
    }

    @Test
    void normalizeBaseUrlKeepsCustomHost() {
        assertEquals("https://api.openai.com",
                                        LocalOpenAiStreamingChatModel.normalizeBaseUrl("https://api.openai.com"));
    }

    @Test
    void textHandlesNull() {
        assertEquals("", LocalOpenAiStreamingChatModel.text(null));
    }

    @Test
    void textHandlesNullNode() {
        var node = Util.MAPPER.createObjectNode().putNull("x");
        assertEquals("", LocalOpenAiStreamingChatModel.text(node.get("x")));
    }

    @Test
    void textReturnsTextValue() throws Exception {
        var node = Util.MAPPER.readTree("\"hello world\"");
        assertEquals("hello world", LocalOpenAiStreamingChatModel.text(node));
    }

    @Test
    void messageCreatesRoleAndContent() {
        ObjectNode msg = LocalOpenAiStreamingChatModel.message("user", "hello");
        assertEquals("user", msg.get("role").asText());
        assertEquals("hello", msg.get("content").asText());
    }

    @Test
    void messageHandlesNullContent() {
        ObjectNode msg = LocalOpenAiStreamingChatModel.message("assistant", null);
        assertEquals("assistant", msg.get("role").asText());
        assertEquals("", msg.get("content").asText());
    }

    @Test
    void toolCallBuildsJson() {
        var call = ToolExecutionRequest.builder().id("call-1").name("search").arguments("{\"q\":\"test\"}").build();
        ObjectNode tc = LocalOpenAiStreamingChatModel.toolCall(call);
        assertEquals("call-1", tc.get("id").asText());
        assertEquals("function", tc.get("type").asText());
        assertEquals("search", tc.get("function").get("name").asText());
        assertEquals("{\"q\":\"test\"}", tc.get("function").get("arguments").asText());
    }

    @Test
    void toolCallDefaultsEmptyArguments() {
        var call = ToolExecutionRequest.builder().id("call-2").name("empty").arguments(null).build();
        ObjectNode tc = LocalOpenAiStreamingChatModel.toolCall(call);
        assertEquals("{}", tc.get("function").get("arguments").asText());
    }

    @Test
    void userTextReturnsSingleText() {
        UserMessage msg = UserMessage.from("simple text");
        assertEquals("simple text", LocalOpenAiStreamingChatModel.userText(msg));
    }

    @Test
    void userTextJoinsMultiContent() {
        UserMessage msg = UserMessage.from("part1", "part2");
        String result = LocalOpenAiStreamingChatModel.userText(msg);
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void usageExtractsTokens() throws Exception {
        var event = Util.MAPPER.readTree(
                                        "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}");
        TokenUsage usage = LocalOpenAiStreamingChatModel.usage(event, null);
        assertNotNull(usage);
        assertEquals(10, usage.inputTokenCount());
        assertEquals(20, usage.outputTokenCount());
        assertEquals(30, usage.totalTokenCount());
    }

    @Test
    void usageReturnsCurrentWhenNoUsageField() throws Exception {
        TokenUsage current = new TokenUsage(5, 10, 15);
        var event = Util.MAPPER.readTree("{}");
        TokenUsage result = LocalOpenAiStreamingChatModel.usage(event, current);
        assertSame(current, result);
    }

    @Test
    void usageReturnsCurrentWhenUsageIsNull() throws Exception {
        TokenUsage current = new TokenUsage(1, 2, 3);
        var node = Util.MAPPER.createObjectNode();
        node.putNull("usage");
        TokenUsage result = LocalOpenAiStreamingChatModel.usage(node, current);
        assertSame(current, result);
    }

    @Test
    void toolCallBuilderBuildsRequest() {
        var b = new LocalOpenAiStreamingChatModel.ToolCallBuilder();
        b.id = "call-1";
        b.name = "search";
        b.arguments = new StringBuilder("{\"q\":\"test\"}");

        ToolExecutionRequest req = b.build();
        assertEquals("call-1", req.id());
        assertEquals("search", req.name());
        assertEquals("{\"q\":\"test\"}", req.arguments());
    }

    @Test
    void toolCallBuilderDefaultsEmptyArguments() {
        var b = new LocalOpenAiStreamingChatModel.ToolCallBuilder();
        b.id = "call-2";
        b.name = "empty";

        ToolExecutionRequest req = b.build();
        assertEquals("{}", req.arguments());
    }

    @Test
    void collectToolCallsHandlesNull() {
        var out = new HashMap<Integer, LocalOpenAiStreamingChatModel.ToolCallBuilder>();
        LocalOpenAiStreamingChatModel.collectToolCalls(null, out);
        assertTrue(out.isEmpty());
    }

    @Test
    void collectToolCallsPopulatesBuilder() throws Exception {
        var json = Util.MAPPER.readTree("[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"search\",\"arguments\":\"simple\"}}]");
        var out = new HashMap<Integer, LocalOpenAiStreamingChatModel.ToolCallBuilder>();

        LocalOpenAiStreamingChatModel.collectToolCalls(json, out);

        assertEquals(1, out.size());
        var b = out.get(0);
        assertNotNull(b);
        assertEquals("call-1", b.id);
        assertEquals("search", b.name);
    }

    @Test
    void collectToolCallsAppendsArguments() throws Exception {
        var json1 = Util.MAPPER.readTree("[{\"index\":0,\"function\":{\"arguments\":\"hello\"}}]");
        var json2 = Util.MAPPER.readTree("[{\"index\":0,\"function\":{\"arguments\":\"world\"}}]");
        var out = new HashMap<Integer, LocalOpenAiStreamingChatModel.ToolCallBuilder>();

        LocalOpenAiStreamingChatModel.collectToolCalls(json1, out);
        LocalOpenAiStreamingChatModel.collectToolCalls(json2, out);

        assertEquals(1, out.size());
        assertTrue(out.get(0).arguments.toString().contains("hello"));
        assertTrue(out.get(0).arguments.toString().contains("world"));
    }

    @Test
    void messagesConvertsSystemAndUser() {
        var msgs = List.<dev.langchain4j.data.message.ChatMessage>of(
                                        dev.langchain4j.data.message.SystemMessage.from("be helpful"),
                                        UserMessage.from("hello"));
        var result = LocalOpenAiStreamingChatModel.messages(msgs);
        assertEquals(2, result.size());
        assertEquals("system", result.get(0).get("role").asText());
        assertEquals("user", result.get(1).get("role").asText());
    }

    @Test
    void messagesIncludesToolCalls() {
        var call = ToolExecutionRequest.builder().id("c1").name("search").arguments("{}").build();
        var msgs = List.<dev.langchain4j.data.message.ChatMessage>of(AiMessage.from("thinking", List.of(call)));
        var result = LocalOpenAiStreamingChatModel.messages(msgs);
        assertEquals(1, result.size());
        assertTrue(result.get(0).has("tool_calls"));
    }

    @Test
    void messagesIncludesToolResults() {
        var msgs = List.<dev.langchain4j.data.message.ChatMessage>of(
                                        ToolExecutionResultMessage.from("c1", "search", "result"));
        var result = LocalOpenAiStreamingChatModel.messages(msgs);
        assertEquals(1, result.size());
        assertEquals("tool", result.get(0).get("role").asText());
        assertEquals("c1", result.get(0).get("tool_call_id").asText());
    }

    @Test
    void toolsConvertsSpecsToFunctions() {
        var spec = ToolSpecification.builder().name("bash").description("Run command").build();
        var tools = LocalOpenAiStreamingChatModel.tools(List.of(spec));
        assertEquals(1, tools.size());
        assertEquals("function", tools.get(0).get("type").asText());
        assertNotNull(tools.get(0).get("function"));
    }

    @Test
    void processDeltaHandlesTextContent() throws Exception {
        var delta = Util.MAPPER.readTree("{\"content\":\"hello world\"}");
        var content = new StringBuilder();
        var thinking = new StringBuilder();
        var toolCalls = new HashMap<Integer, LocalOpenAiStreamingChatModel.ToolCallBuilder>();

        var result = LocalOpenAiStreamingChatModel.processDelta(delta, content, thinking, toolCalls);

        assertEquals("hello world", result.textDelta());
        assertEquals("hello world", content.toString());
        assertNull(result.thinkingDelta());
    }

    @Test
    void processDeltaHandlesReasoning() throws Exception {
        var delta = Util.MAPPER.readTree("{\"reasoning_content\":\"thinking...\"}");
        var content = new StringBuilder();
        var thinking = new StringBuilder();
        var toolCalls = new HashMap<Integer, LocalOpenAiStreamingChatModel.ToolCallBuilder>();

        var result = LocalOpenAiStreamingChatModel.processDelta(delta, content, thinking, toolCalls);

        assertEquals("thinking...", result.thinkingDelta());
        assertEquals("thinking...", thinking.toString());
        assertNull(result.textDelta());
    }

    @Test
    void processDeltaHandlesToolCalls() throws Exception {
        var delta = Util.MAPPER.readTree(
                                        "{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}}]}");
        var content = new StringBuilder();
        var thinking = new StringBuilder();
        var toolCalls = new HashMap<Integer, LocalOpenAiStreamingChatModel.ToolCallBuilder>();

        var result = LocalOpenAiStreamingChatModel.processDelta(delta, content, thinking, toolCalls);

        assertEquals(1, toolCalls.size());
        assertEquals("call-1", toolCalls.get(0).id);
        assertNull(result.textDelta());
        assertNull(result.thinkingDelta());
    }

    @Test
    void buildRequestBodySetsModelAndStream() {
        var msgs = List.<dev.langchain4j.data.message.ChatMessage>of(UserMessage.from("hello"));
        var request = dev.langchain4j.model.chat.request.ChatRequest.builder().messages(msgs).build();

        var body = LocalOpenAiStreamingChatModel.buildRequestBody(request, "gpt-4", ThinkingLevel.OFF);
        assertEquals("gpt-4", body.get("model").asText());
        assertTrue(body.get("stream").asBoolean());
    }

    @Test
    void buildRequestBodyEnablesThinkingForNonOffLevel() {
        var msgs = List.<dev.langchain4j.data.message.ChatMessage>of(UserMessage.from("hello"));
        var request = dev.langchain4j.model.chat.request.ChatRequest.builder().messages(msgs).build();

        var body = LocalOpenAiStreamingChatModel.buildRequestBody(request, "gpt-4", ThinkingLevel.HIGH);
        assertTrue(body.get("enable_thinking").asBoolean());
        assertTrue(body.get("chat_template_kwargs").get("enable_thinking").asBoolean());
    }

    @Test
    void buildRequestBodyIncludesToolsWhenPresent() {
        var msgs = List.<dev.langchain4j.data.message.ChatMessage>of(UserMessage.from("hello"));
        var spec = ToolSpecification.builder().name("search").description("Search").build();
        var request = dev.langchain4j.model.chat.request.ChatRequest.builder().messages(msgs)
                                        .toolSpecifications(List.of(spec)).build();

        var body = LocalOpenAiStreamingChatModel.buildRequestBody(request, "gpt-4", ThinkingLevel.OFF);
        assertTrue(body.has("tools"));
        assertEquals("auto", body.get("tool_choice").asText());
    }

    @Test
    void processDeltaHandlesBothContentAndThinking() throws Exception {
        var delta = Util.MAPPER.readTree("{\"content\":\"hello\",\"reasoning_content\":\"thinking...\"}");
        var content = new StringBuilder();
        var thinking = new StringBuilder();
        var toolCalls = new HashMap<Integer, LocalOpenAiStreamingChatModel.ToolCallBuilder>();

        var result = LocalOpenAiStreamingChatModel.processDelta(delta, content, thinking, toolCalls);

        assertEquals("hello", result.textDelta());
        assertEquals("thinking...", result.thinkingDelta());
        assertEquals("hello", content.toString());
        assertEquals("thinking...", thinking.toString());
    }

    @Test
    void toolsHandlesSpecWithoutParameters() {
        var spec = ToolSpecification.builder().name("no-params").description("No params").build();

        var result = LocalOpenAiStreamingChatModel.tools(List.of(spec));
        assertEquals(1, result.size());
        assertEquals("function", result.get(0).get("type").asText());
    }

    @Test
    void collectToolCallsUsesMapSizeWhenNoIndex() throws Exception {
        var json = Util.MAPPER.readTree("[{\"id\":\"c1\",\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}}]");
        var out = new HashMap<Integer, LocalOpenAiStreamingChatModel.ToolCallBuilder>();

        LocalOpenAiStreamingChatModel.collectToolCalls(json, out);

        assertEquals(1, out.size());
        assertNotNull(out.get(0));
        assertEquals("c1", out.get(0).id);
    }

    @Test
    void collectToolCallsHandlesSecondItemAtNextIndex() throws Exception {
        var json1 = Util.MAPPER.readTree("[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"a\"}}]");
        var json2 = Util.MAPPER.readTree("[{\"index\":1,\"id\":\"c2\",\"function\":{\"name\":\"b\"}}]");
        var out = new HashMap<Integer, LocalOpenAiStreamingChatModel.ToolCallBuilder>();

        LocalOpenAiStreamingChatModel.collectToolCalls(json1, out);
        LocalOpenAiStreamingChatModel.collectToolCalls(json2, out);

        assertEquals(2, out.size());
        assertEquals("c1", out.get(0).id);
        assertEquals("c2", out.get(1).id);
    }
}
