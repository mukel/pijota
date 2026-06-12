package ai.pijota.agent;

import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;
import ai.pijota.testkit.FauxLlm;
import ai.pijota.tools.Tool;
import ai.pijota.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {
    @Test
    void streamsAssistantDeltasAndStoresFinalAssistantMessage() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content("hel", "lo"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();
        var events = recordEvents(agent);

        var messages = agent.prompt("hi").get(1, TimeUnit.SECONDS);

        assertEquals(2, messages.size());
        assertEquals("hi", ((Message.User) messages.get(0)).content());
        assertEquals("hello", ((Message.Assistant) messages.get(1)).content());
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.MessageStreamStart));
        assertTrue(events.stream().anyMatch(
                                        e -> e instanceof AgentEvent.MessageStreamDelta d && d.delta().equals("hel")));
        assertTrue(events.stream().anyMatch(
                                        e -> e instanceof AgentEvent.MessageStreamDelta d && d.delta().equals("lo")));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.MessageStreamEnd));
    }

    @Test
    void storesStreamedThinkingOnAssistantMessage() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.thinkingAndContent(List.of("think ", "more"), "answer"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();

        var messages = agent.prompt("question").get(1, TimeUnit.SECONDS);

        var assistant = (Message.Assistant) messages.get(1);
        assertEquals("answer", assistant.content());
        assertEquals("think more", assistant.thinking());
    }

    @Test
    void executesToolCallsAndContinuesWithToolResultInContext() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.toolCall(new ToolCall("tc1", "fake", "{\"value\":\"ok\"}")))
                                        .enqueue(FauxLlm.Response.content("done"));
        var registry = new ToolRegistry();
        registry.register(fakeTool());
        var agent = AgentLoop.builder(llm, registry, "/workspace").build();
        var events = recordEvents(agent);

        var messages = agent.prompt("use tool").get(1, TimeUnit.SECONDS);

        assertEquals(4, messages.size());
        assertInstanceOf(Message.ToolResult.class, messages.get(2));
        assertEquals("tool:ok", ((Message.ToolResult) messages.get(2)).content());
        assertEquals("done", ((Message.Assistant) messages.get(3)).content());
        assertEquals(2, llm.requests().size());
        assertTrue(llm.requests().get(1).messages().stream().anyMatch(m -> m instanceof Message.ToolResult));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolStart ts && ts.toolName().equals("fake")));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolResult tr
                                        && tr.message().content().equals("tool:ok")));
    }

    @Test
    void omitsToolsFromRequestWhenLlmDoesNotStreamWithTools() throws Exception {
        var llm = new FauxLlm().streamsWithTools(false).enqueue(FauxLlm.Response.content("ok"));
        var registry = new ToolRegistry();
        registry.register(fakeTool());
        var agent = AgentLoop.builder(llm, registry, "/workspace").build();

        agent.prompt("hi").get(1, TimeUnit.SECONDS);

        assertTrue(llm.requests().getFirst().tools().isEmpty());
    }

    @Test
    void abortCancelsActiveStreamAndCompletesWithoutException() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content("partial")).blockUntilAborted();
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();

        var future = agent.prompt("start");
        Thread.sleep(100);
        agent.abort();

        assertDoesNotThrow(() -> future.get(1, TimeUnit.SECONDS));
        assertTrue(llm.aborted());
    }

    @Test
    void followUpMessageRunsAfterToolLoopCompletes() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.toolCall(new ToolCall("tc1", "fake", "{\"value\":\"ok\"}")))
                                        .enqueue(FauxLlm.Response.content("after tool"))
                                        .enqueue(FauxLlm.Response.content("follow up answer"));
        var registry = new ToolRegistry();
        registry.register(fakeTool());
        var agent = AgentLoop.builder(llm, registry, "/workspace").build();

        agent.followUp("follow this up");
        var messages = agent.prompt("use tool").get(2, TimeUnit.SECONDS);

        assertEquals(6, messages.size(), "user, assistant(toolCall), toolResult, assistant, user(followUp), assistant");
        assertEquals("follow this up", ((Message.User) messages.get(4)).content());
        assertEquals("follow up answer", ((Message.Assistant) messages.get(5)).content());
    }

    private static List<AgentEvent> recordEvents(AgentLoop agent) {
        var events = new ArrayList<AgentEvent>();
        agent.addEventListener(events::add);
        return events;
    }

    private static Tool fakeTool() {
        return new Tool.ToolDefinition("fake", "Fake test tool",
                                        Map.of("type", "object", "properties",
                                                                        Map.of("value", Map.of("type", "string"))),
                                        (_, args, _) -> CompletableFuture.completedFuture(Tool.ToolResult
                                                                        .success("tool:" + args.get("value"))))
                                        .toTool();
    }
}
