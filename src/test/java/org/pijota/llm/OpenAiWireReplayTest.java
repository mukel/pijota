package org.pijota.llm;

import org.pijota.messages.Message;
import org.pijota.testkit.WireFixture;
import org.pijota.tools.Tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays recorded OpenAI-compatible responses through the real request/response path. This is the family most
 * providers use (deepseek, groq, openrouter, xai, z.ai, …), so a regression here is a regression almost everywhere.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OpenAiWireReplayTest {

    private WireFixture wire;

    @BeforeEach
    void startFixture() throws Exception {
        wire = WireFixture.start();
    }

    @AfterEach
    void stopFixture() {
        if (wire != null)
            wire.close();
    }

    private LlmService service() {
        return new LlmService("deepseek", "deepseek-chat", p -> "test-key", wire.baseUrl(), List.of(), null);
    }

    private static Message.User user(String text) {
        return new Message.User(UUID.randomUUID(), Instant.now(), text, List.of());
    }

    private StreamResult run(LlmService llm) throws Exception {
        return llm.streamMessages(List.of(user("hello")), List.of(), (a, b) -> {}).get(20, TimeUnit.SECONDS);
    }

    /** The baseline: streamed content deltas assemble into one message, and usage lands. */
    @Test
    void assemblesStreamedDeltasAndUsage() throws Exception {
        wire.replaySse(WireFixture.sse("{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"}}]}",
                                        "{\"choices\":[{\"delta\":{\"content\":\"lo \"}}]}",
                                        "{\"choices\":[{\"delta\":{\"content\":\"world\"}}]}",
                                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                                                                        + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3,\"total_tokens\":14}}",
                                        "[DONE]"));

        StreamResult result = run(service());

        assertEquals("Hello world", result.content(), "deltas must concatenate in order");
        assertNotNull(result.usage(), "usage is what budgets and the context meter are built on");
        assertEquals(11, result.usage().inputTokens());
        assertEquals(3, result.usage().outputTokens());
        assertFalse(result.hasToolCalls());
    }

    /** A tool call arrives as fragments across events; the arguments must be reassembled, not truncated. */
    @Test
    void reassemblesAToolCallSplitAcrossEvents() throws Exception {
        wire.replaySse(WireFixture.sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_abc\","
                                        + "\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"\"}}]}}]}",
                                        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                                                                        + "{\"arguments\":\"{\\\"path\\\":\"}}]}}]}",
                                        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                                                                        + "{\"arguments\":\"\\\"a.py\\\"}\"}}]}}]}",
                                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],"
                                                                        + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":8,\"total_tokens\":28}}",
                                        "[DONE]"));

        StreamResult result = run(service());

        assertTrue(result.hasToolCalls(), "a tool call must survive being split across events");
        assertEquals(1, result.toolCalls().size());
        var call = result.toolCalls().get(0);
        assertEquals("read", call.name());
        assertEquals("call_abc", call.id());
        assertEquals("{\"path\":\"a.py\"}", call.arguments(), "fragments must reassemble into valid JSON");
    }

    /** Two calls in one turn are the batching path; they must not merge into one. */
    @Test
    void keepsParallelToolCallsSeparate() throws Exception {
        wire.replaySse(WireFixture.sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
                                        + "\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"a\\\"}\"}}]}}]}",
                                        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"c2\","
                                                                        + "\"type\":\"function\",\"function\":{\"name\":\"ls\",\"arguments\":\"{}\"}}]}}]}",
                                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]"));

        StreamResult result = run(service());

        assertEquals(2, result.toolCalls().size(), "parallel calls must stay distinct: " + result.toolCalls());
        assertEquals(List.of("read", "ls"), result.toolCalls().stream().map(c -> c.name()).toList());
        assertEquals(List.of("c1", "c2"), result.toolCalls().stream().map(c -> c.id()).toList());
    }

    /**
     * Cached prompt tokens are billed at roughly a tenth of fresh input, so dropping them silently overstates cost and
     * understates how much of the prefix is being reused.
     */
    @Test
    void recordsCachedPromptTokensSeparatelyFromFreshInput() throws Exception {
        wire.replaySse(WireFixture.sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}",
                                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":"
                                                                        + "{\"prompt_tokens\":1000,\"completion_tokens\":5,\"total_tokens\":1005,"
                                                                        + "\"prompt_tokens_details\":{\"cached_tokens\":900}}}",
                                        "[DONE]"));

        StreamResult result = run(service());

        assertNotNull(result.usage());
        assertEquals(900, result.usage().cacheReadTokens(), "cached tokens must be reported separately");
        assertEquals(5, result.usage().outputTokens());
    }

    /** Reasoning models bill thinking inside output tokens; it is surfaced so a thinking-heavy turn is visible. */
    @Test
    void surfacesReasoningTokens() throws Exception {
        wire.replaySse(WireFixture.sse("{\"choices\":[{\"delta\":{\"content\":\"42\"}}]}",
                                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":"
                                                                        + "{\"prompt_tokens\":10,\"completion_tokens\":120,\"total_tokens\":130,"
                                                                        + "\"completion_tokens_details\":{\"reasoning_tokens\":100}}}",
                                        "[DONE]"));

        StreamResult result = run(service());

        assertEquals(100, result.usage().reasoningTokens(), "reasoning tokens are a subset of output, still reported");
    }

    /** The request half of the contract: auth, model and the user's text must actually leave the machine. */
    @Test
    void sendsTheKeyModelAndPromptOnTheWire() throws Exception {
        wire.replaySse(WireFixture.sse("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}", "[DONE]"));

        run(service());

        var request = wire.lastRequest();
        assertEquals("POST", request.method());
        assertEquals("Bearer test-key", request.header("Authorization"), "the resolved key must be sent");
        assertTrue(request.body().contains("\"deepseek-chat\""), "the model must be named: " + request.body());
        assertTrue(request.body().contains("hello"), "the prompt must be sent: " + request.body());
        String body = request.body().replaceAll("\\s+", ""); // the client pretty-prints; compare without whitespace
        assertTrue(body.contains("\"stream\":true"), "streaming must be requested: " + request.body());
        // Without stream_options.include_usage the provider sends no usage block at all, and every token count,
        // the context meter and the cost budget silently read zero.
        assertTrue(body.contains("\"include_usage\":true"), "usage must be requested: " + request.body());
    }

    /** Tool schemas are rebuilt per request; a malformed one fails at the provider, not locally. */
    @Test
    void sendsToolSchemasInTheProvidersShape() throws Exception {
        wire.replaySse(WireFixture.sse("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}", "[DONE]"));
        Tool tool = Tool.named("read").description("Read a file").stringParam("path", "File to read", true)
                                        .run((args, cwd) -> Tool.ToolResult.success("ok")).toTool();

        service().streamMessages(List.of(user("hello")), List.of(tool), (a, b) -> {}).get(20, TimeUnit.SECONDS);

        String body = wire.lastRequest().body();
        assertTrue(body.contains("\"tools\""), "tools must be advertised: " + body);
        assertTrue(body.contains("\"read\"") && body.contains("\"path\""), "schema must survive: " + body);
    }

    /** An empty stream is a provider hiccup, not a crash; the turn must complete with nothing rather than hang. */
    @Test
    void anEmptyStreamCompletesWithoutContent() throws Exception {
        wire.replaySse(WireFixture.sse("[DONE]"));

        StreamResult result = run(service());

        assertNotNull(result);
        assertTrue(result.content() == null || result.content().isEmpty(), "no content, no crash");
        assertFalse(result.hasToolCalls());
    }
}
