package org.pijota.llm;

import static org.junit.jupiter.api.Assertions.*;

import org.pijota.messages.Message;
import org.pijota.testkit.WireFixture;
import org.pijota.tools.Tool;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Replays recorded Anthropic-style responses. This family shares no wire shape with the OpenAI-compatible one: events
 * are named rather than anonymous, content arrives as indexed blocks rather than a delta string, tool arguments stream
 * as {@code input_json_delta} fragments, thinking is a first-class block, and usage is split across the opening and
 * closing events. Every one of those is a separate place to lose data.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AnthropicWireReplayTest {

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
        return new LlmService("anthropic", "claude-sonnet-4-20250514", p -> "test-key", wire.baseUrl(), List.of(),
                                        null);
    }

    private static Message.User user(String text) {
        return new Message.User(UUID.randomUUID(), Instant.now(), text, List.of());
    }

    private StreamResult run() throws Exception {
        return service().streamMessages(List.of(user("hello")), List.of(), (a, b) -> {}).get(20, TimeUnit.SECONDS);
    }

    /** A text turn: message_start carries input usage, deltas carry the text, message_delta carries output usage. */
    @Test
    void assemblesTextBlocksAndUsageSplitAcrossEvents() throws Exception {
        wire.replaySse(WireFixture.sseNamed("message_start",
                                        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"role\":\"assistant\","
                                                                        + "\"content\":[],\"model\":\"claude-sonnet-4-20250514\","
                                                                        + "\"usage\":{\"input_tokens\":11,\"output_tokens\":0}}}",
                                        "content_block_start",
                                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                                        "content_block_delta",
                                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello \"}}",
                                        "content_block_delta",
                                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"world\"}}",
                                        "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}",
                                        "message_delta",
                                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                                                                        + "\"usage\":{\"output_tokens\":3}}",
                                        "message_stop", "{\"type\":\"message_stop\"}"));

        StreamResult result = run();

        assertEquals("Hello world", result.content());
        assertNotNull(result.usage(), "input usage arrives on message_start, output on message_delta");
        assertEquals(11, result.usage().inputTokens());
        assertEquals(3, result.usage().outputTokens());
    }

    /** Tool arguments stream as input_json_delta fragments and must reassemble into the complete JSON. */
    @Test
    void reassemblesToolInputStreamedAsJsonFragments() throws Exception {
        wire.replaySse(WireFixture.sseNamed("message_start",
                                        "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"role\":\"assistant\",\"content\":[],"
                                                                        + "\"usage\":{\"input_tokens\":20,\"output_tokens\":0}}}",
                                        "content_block_start",
                                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                                                                        + "{\"type\":\"tool_use\",\"id\":\"toolu_01\",\"name\":\"read\",\"input\":{}}}",
                                        "content_block_delta",
                                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                                                                        + "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\"}}",
                                        "content_block_delta",
                                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                                                                        + "{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"a.py\\\"}\"}}",
                                        "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}",
                                        "message_delta",
                                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
                                                                        + "\"usage\":{\"output_tokens\":9}}",
                                        "message_stop", "{\"type\":\"message_stop\"}"));

        StreamResult result = run();

        assertTrue(result.hasToolCalls(), "a tool_use block must become a tool call");
        var call = result.toolCalls().get(0);
        assertEquals("read", call.name());
        assertEquals("toolu_01", call.id());
        assertEquals("{\"path\":\"a.py\"}", call.arguments().replaceAll("\\s+", ""),
                                        "input_json_delta fragments must reassemble");
    }

    /** Text and a tool call in one response arrive as two indexed blocks; neither may swallow the other. */
    @Test
    void keepsTextAndAToolCallFromTheSameResponseSeparate() throws Exception {
        wire.replaySse(WireFixture.sseNamed("message_start",
                                        "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"role\":\"assistant\",\"content\":[],"
                                                                        + "\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}",
                                        "content_block_start",
                                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                                        "content_block_delta",
                                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                                                                        + "{\"type\":\"text_delta\",\"text\":\"Reading it.\"}}",
                                        "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}",
                                        "content_block_start",
                                        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":"
                                                                        + "{\"type\":\"tool_use\",\"id\":\"toolu_02\",\"name\":\"ls\",\"input\":{}}}",
                                        "content_block_delta",
                                        "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":"
                                                                        + "{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}",
                                        "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}",
                                        "message_delta",
                                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
                                                                        + "\"usage\":{\"output_tokens\":12}}",
                                        "message_stop", "{\"type\":\"message_stop\"}"));

        StreamResult result = run();

        assertEquals("Reading it.", result.content(), "the text block must survive alongside the tool call");
        assertEquals(1, result.toolCalls().size());
        assertEquals("ls", result.toolCalls().get(0).name());
    }

    /**
     * Thinking is its own block type and must land in thinking(), not be concatenated into the answer. Both halves are
     * checked: the request has to actually enable extended thinking, and the response's thinking blocks have to be kept
     * apart from the text ones. With thinking off, no thinking is requested and none comes back.
     */
    @Test
    void keepsThinkingOutOfTheAnswer() throws Exception {
        wire.replaySse(WireFixture.sseNamed("message_start",
                                        "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"role\":\"assistant\",\"content\":[],"
                                                                        + "\"usage\":{\"input_tokens\":7,\"output_tokens\":0}}}",
                                        "content_block_start",
                                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                                                                        + "{\"type\":\"thinking\",\"thinking\":\"\"}}",
                                        "content_block_delta",
                                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                                                                        + "{\"type\":\"thinking_delta\",\"thinking\":\"Let me consider.\"}}",
                                        "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}",
                                        "content_block_start",
                                        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                                        "content_block_delta",
                                        "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":"
                                                                        + "{\"type\":\"text_delta\",\"text\":\"The answer.\"}}",
                                        "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}",
                                        "message_delta",
                                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                                                                        + "\"usage\":{\"output_tokens\":20}}",
                                        "message_stop", "{\"type\":\"message_stop\"}"));

        var llm = service();
        llm.setThinkingLevel(ThinkingLevel.MEDIUM);
        StreamResult result = llm.streamMessages(List.of(user("hello")), List.of(), (a, b) -> {}).get(20,
                                        TimeUnit.SECONDS);

        String sent = wire.lastRequest().body().replaceAll("\\s+", "");
        assertTrue(sent.contains("\"thinking\""), "extended thinking must be requested: " + wire.lastRequest().body());
        assertTrue(sent.contains("\"budget_tokens\":8192"), "the level's budget must be sent: " + sent);

        assertEquals("The answer.", result.content(), "reasoning must not leak into the answer text");
        assertNotNull(result.thinking());
        assertTrue(result.thinking().contains("Let me consider."), "thinking was dropped: " + result.thinking());
    }

    /** With thinking off, nothing is requested -- the default must not silently pay for reasoning tokens. */
    @Test
    void thinkingIsNotRequestedWhenItIsOff() throws Exception {
        wire.replaySse(WireFixture.sseNamed("message_start",
                                        "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"role\":\"assistant\",\"content\":[],"
                                                                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}",
                                        "message_stop", "{\"type\":\"message_stop\"}"));

        run();

        assertFalse(wire.lastRequest().body().replaceAll("\\s+", "").contains("\"budget_tokens\""),
                                        "a thinking budget must not be sent when thinking is off");
    }

    /**
     * Cache writes and reads are reported on their own fields here, and the whole point of cacheSystemMessages is that
     * they show up: a cache read costs about a tenth of fresh input.
     */
    @Test
    void recordsCacheCreationAndReadTokens() throws Exception {
        wire.replaySse(WireFixture.sseNamed("message_start",
                                        "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"role\":\"assistant\",\"content\":[],"
                                                                        + "\"usage\":{\"input_tokens\":12,\"output_tokens\":0,"
                                                                        + "\"cache_creation_input_tokens\":2000,\"cache_read_input_tokens\":800}}}",
                                        "content_block_start",
                                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                                        "content_block_delta",
                                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}",
                                        "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}",
                                        "message_delta",
                                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                                                                        + "\"usage\":{\"output_tokens\":2}}",
                                        "message_stop", "{\"type\":\"message_stop\"}"));

        StreamResult result = run();

        assertEquals(800, result.usage().cacheReadTokens(), "cache reads must be tracked");
        assertEquals(2000, result.usage().cacheWriteTokens(), "cache writes must be tracked");
    }

    /** The request half: this family authenticates with x-api-key and a version header, not a bearer token. */
    @Test
    void sendsTheAnthropicAuthAndVersionHeaders() throws Exception {
        wire.replaySse(WireFixture.sseNamed("message_start",
                                        "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"role\":\"assistant\",\"content\":[],"
                                                                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}",
                                        "message_stop", "{\"type\":\"message_stop\"}"));

        run();

        var request = wire.lastRequest();
        assertEquals("test-key", request.header("X-api-key"), "this family does not use Authorization: Bearer");
        assertEquals("2023-06-01", request.header("Anthropic-version"), "the version header is required by the API");
        assertTrue(request.body().contains("claude-sonnet-4-20250514"), request.body());
    }

    /**
     * cacheSystemMessages/cacheTools are on so the large static prefix is not re-billed every turn. That only works if
     * the request actually carries cache_control markers, which nothing else checks.
     */
    @Test
    void marksThePromptPrefixForCaching() throws Exception {
        wire.replaySse(WireFixture.sseNamed("message_start",
                                        "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"role\":\"assistant\",\"content\":[],"
                                                                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}",
                                        "message_stop", "{\"type\":\"message_stop\"}"));
        Tool tool = Tool.named("read").description("Read a file").stringParam("path", "File to read", true)
                                        .run((args, cwd) -> Tool.ToolResult.success("ok")).toTool();

        service().streamMessages(List.of(new Message.System(UUID.randomUUID(), Instant.now(), "You are pijota."),
                                        user("hello")), List.of(tool), (a, b) -> {}).get(20, TimeUnit.SECONDS);

        String body = wire.lastRequest().body().replaceAll("\\s+", "");
        assertTrue(body.contains("cache_control"), "the cached prefix must be marked, or every turn re-bills it: "
                                        + wire.lastRequest().body());
    }

    /** A provider error must surface as a failure, not as an empty successful turn. */
    @Test
    void anErrorBodyFailsTheTurnRatherThanReturningEmpty() throws Exception {
        wire.replayError(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                                        + "\"message\":\"invalid x-api-key\"}}");

        var failure = assertThrows(Exception.class, this::run, "a 401 must not look like a successful empty turn");
        String text = String.valueOf(failure.getCause() != null
                                        ? failure.getCause().getMessage()
                                        : failure.getMessage());
        assertFalse(text.isBlank(), "the failure must carry something a user can act on");
    }
}
