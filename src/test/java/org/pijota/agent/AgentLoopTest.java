package org.pijota.agent;

import org.pijota.config.AppConfig;
import org.pijota.extensions.DeliverAs;
import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionRegistry;
import org.pijota.messages.Message;
import org.pijota.messages.ToolCall;
import org.pijota.messages.Usage;
import org.pijota.testkit.Await;
import org.pijota.testkit.EventRecorder;
import org.pijota.testkit.FauxLlm;
import org.pijota.testkit.TestTools;
import org.pijota.tools.ToolRegistry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {
    @Test
    void streamsAssistantDeltasAndStoresFinalAssistantMessage() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content("hel", "lo"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();
        var events = EventRecorder.capture(agent);

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
        registry.register(TestTools.echoing("fake"));
        var agent = AgentLoop.builder(llm, registry, "/workspace").build();
        var events = EventRecorder.capture(agent);

        var messages = agent.prompt("use tool").get(1, TimeUnit.SECONDS);

        assertTrue(messages.stream().anyMatch(
                                        m -> m instanceof Message.ToolResult tr && tr.content().equals("tool:ok")),
                                        "should contain tool result");
        assertTrue(messages.stream().anyMatch(m -> m instanceof Message.Assistant a && a.content().equals("done")),
                                        "should contain final assistant response");
        assertEquals(2, llm.requests().size());
        assertTrue(llm.requests().get(1).messages().stream().anyMatch(m -> m instanceof Message.ToolResult));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolStart ts && ts.toolName().equals("fake")));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolResult tr
                                        && tr.message().content().equals("tool:ok")));
    }

    @Test
    void abortCancelsActiveStreamAndCompletesWithoutException() throws Exception {
        var llm = new FauxLlm().blockUntilAborted();
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();

        var future = agent.prompt("start");
        Await.until(agent::isBusy);
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
        registry.register(TestTools.echoing("fake"));
        var agent = AgentLoop.builder(llm, registry, "/workspace").build();

        agent.followUp("follow this up");
        var messages = agent.prompt("use tool").get(2, TimeUnit.SECONDS);

        assertEquals(6, messages.size(), "user, assistant(toolCall), toolResult, assistant, user(followUp), assistant");
        assertEquals("follow this up", ((Message.User) messages.get(4)).content());
        assertEquals("follow up answer", ((Message.Assistant) messages.get(5)).content());
    }

    @Test
    void steeringMessageIsProcessedAtStartOfTurn() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content("ok"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();

        agent.sendUserMessage("steer me", DeliverAs.STEER);
        var messages = agent.prompt("do it").get(1, TimeUnit.SECONDS);

        assertEquals(3, messages.size());
        assertEquals("do it", ((Message.User) messages.get(0)).content());
        assertEquals("steer me", ((Message.User) messages.get(1)).content());
        assertEquals("ok", ((Message.Assistant) messages.get(2)).content());
    }

    @Test
    void sendUserMessageWithFollowUpAddsToFollowUpQueue() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content("first")).enqueue(FauxLlm.Response.content("second"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();

        agent.sendUserMessage("follow this", DeliverAs.FOLLOW_UP);
        var messages = agent.prompt("hi").get(2, TimeUnit.SECONDS);

        assertEquals(4, messages.size());
        assertEquals("follow this", ((Message.User) messages.get(2)).content());
        assertEquals("second", ((Message.Assistant) messages.get(3)).content());
    }

    @Test
    void addCustomMessageAddsMessageAndFiresEvent() {
        var agent = AgentLoop.builder(new FauxLlm(), new ToolRegistry(), "/workspace").build();
        var events = EventRecorder.capture(agent);

        agent.addCustomMessage(new Message.Custom("note", "value"));

        assertTrue(agent.messages().stream()
                                        .anyMatch(m -> m instanceof Message.Custom c && c.customType().equals("note")
                                                                        && c.content().equals("value")),
                                        "should contain the added custom message");
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.CustomMessage));
    }

    @Test
    void messagesReturnsStableImmutableSnapshot() {
        var agent = AgentLoop.builder(new FauxLlm(), new ToolRegistry(), "/workspace").build();
        agent.addCustomMessage(new Message.Custom("note", "first"));

        var messages = agent.messages();
        agent.addCustomMessage(new Message.Custom("note", "second"));

        assertEquals(1, messages.size());
        assertThrows(UnsupportedOperationException.class, () -> messages.add(new Message.User("mutate")));
    }

    @Test
    void replaceSystemPromptUpdatesExistingSystemMessage() {
        var agent = AgentLoop.builder(new FauxLlm(), new ToolRegistry(), "/workspace").build();
        agent.addSystemPrompt("v1");

        agent.replaceSystemPrompt("v2");

        assertEquals("v2", ((Message.System) agent.messages().get(0)).content());
    }

    @Test
    void undoLastUserTurnRemovesUserAndFollowingMessages() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content("a1")).enqueue(FauxLlm.Response.content("a2"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();
        agent.prompt("first").get(1, TimeUnit.SECONDS);
        agent.prompt("second").get(1, TimeUnit.SECONDS);

        var undone = agent.undoLastUserTurn();

        assertTrue(undone.isPresent());
        assertEquals("second", undone.get());
        assertTrue(agent.messages().stream().anyMatch(m -> m instanceof Message.User u && u.content().equals("first")),
                                        "should still contain first user message after undo");
        assertTrue(agent.messages().stream()
                                        .noneMatch(m -> m instanceof Message.User u && u.content().equals("second")),
                                        "should not contain undone second user message");
    }

    @Test
    void lastAssistantContentFindsLatestAssistantText() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content("first")).enqueue(FauxLlm.Response.content("second"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();
        agent.prompt("q1").get(1, TimeUnit.SECONDS);
        agent.prompt("q2").get(1, TimeUnit.SECONDS);

        assertEquals("second", agent.lastAssistantContent());
    }

    @Test
    void listenerExceptionsDoNotPreventOtherListenersFromReceivingEvents() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content("hi"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();
        var goodEvents = new ArrayList<AgentEvent>();
        agent.addEventListener(e -> {
            throw new RuntimeException("boom");
        });
        agent.addEventListener(goodEvents::add);

        agent.prompt("hello").get(1, TimeUnit.SECONDS);

        assertFalse(goodEvents.isEmpty());
    }

    @Test
    void removeEventListenerStopsDelivery() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content("hi"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();
        var events = new ArrayList<AgentEvent>();
        Consumer<AgentEvent> listener = events::add;
        agent.addEventListener(listener);
        agent.removeEventListener(listener);

        agent.prompt("hello").get(1, TimeUnit.SECONDS);

        assertTrue(events.isEmpty());
    }

    @Test
    void retryRecoversFromRetryableError() throws Exception {
        var llm = new FauxLlm().enqueueError("server error 500").enqueue(FauxLlm.Response.content("ok"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").retryBaseDelayMs(1).build();

        var messages = agent.prompt("hi").get(2, TimeUnit.SECONDS);

        assertEquals("ok", ((Message.Assistant) messages.get(1)).content());
        assertEquals(2, llm.requests().size());
    }

    @Test
    void retryGivesUpAfterMaxAttempts() {
        var llm = new FauxLlm();
        for (int i = 0; i < 6; i++)
            llm.enqueueError("server error 500");
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").retryBaseDelayMs(1).build();

        assertThrows(ExecutionException.class, () -> agent.prompt("hi").get(5, TimeUnit.SECONDS));
        assertEquals(6, llm.requests().size()); // initial + 5 retries, then give up
    }

    @Test
    void nonRetryableErrorIsNotRetried() {
        var llm = new FauxLlm().enqueueError(new RuntimeException("401 unauthorized"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").retryBaseDelayMs(1).build();

        assertThrows(ExecutionException.class, () -> agent.prompt("hi").get(2, TimeUnit.SECONDS));
        assertEquals(1, llm.requests().size()); // 4xx is not retryable
    }

    @Test
    void contextOverheadEstimatesToolSchemasBeforeAnyResponse() {
        var registry = new ToolRegistry();
        registry.register(TestTools.echoing("fake"));
        var withTools = AgentLoop.builder(new FauxLlm(), registry, "/workspace").build();
        var noTools = AgentLoop.builder(new FauxLlm(), new ToolRegistry(), "/workspace").build();

        // Fresh session (no real usage yet): overhead is estimated from the enabled tools' schemas, not left at 0.
        assertTrue(withTools.contextOverhead() > 0, "tool schemas should be estimated up front");
        assertEquals(0, noTools.contextOverhead(), "no tools → no estimated overhead");
    }

    @Test
    void restoreMessagesResetsTheStaleContextOverheadAnchor() throws Exception {
        // The overhead anchor is measured against a specific message set; replacing that set (restore/clear/compact)
        // must drop the anchor, or its stale gap keeps inflating the count after the content it absorbed is gone.
        var llm = new FauxLlm();
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();
        long baseline = agent.contextOverhead(); // no tools → 0

        // A large conversation, then a real response whose promptTokens (50k) far exceed the byte estimate → big
        // anchor.
        agent.restoreMessages(List.of(new Message.System("sys"), new Message.User("x".repeat(4000))));
        llm.enqueue(FauxLlm.Response.content(new Usage(50_000, 10), "ok"));
        agent.prompt("hi").get(2, TimeUnit.SECONDS);
        long anchored = agent.contextOverhead();
        assertTrue(anchored > baseline, "a real response anchors the overhead above baseline: " + anchored);

        // Replacing the messages (what compaction does) must reset the anchor to the pre-anchor baseline.
        agent.restoreMessages(List.of(new Message.System("sys")));
        assertEquals(baseline, agent.contextOverhead(),
                                        "replacing the message set must reset the stale overhead anchor");
    }

    @Test
    void compactionResetsTheStaleContextOverheadAnchor() throws Exception {
        var summarizer = (CompactionManager.SummaryGenerator) (msgs, instr, prev, onPart) -> "summary";
        var config = new AppConfig.CompactionConfig(true, 100, 1);
        var llm = new FauxLlm();
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").summaryGenerator(summarizer)
                                        .compactionConfig(config).contextWindow(1000).build();

        agent.addSystemPrompt("sys");
        agent.sendUserMessage("old");
        agent.sendUserMessage("middle");
        agent.sendUserMessage("recent big " + "x".repeat(4000));
        // Anchor an inflated overhead: the provider reports a 50k prompt for a much smaller byte estimate.
        llm.enqueue(FauxLlm.Response.content(new Usage(50_000, 10), "ok"));
        agent.prompt("hi").get(2, TimeUnit.SECONDS);
        long anchored = agent.contextOverhead();
        assertTrue(anchored > 0, "a real response anchors the overhead: " + anchored);

        var result = agent.compactWithResult("compact");

        assertTrue(result.compacted());
        assertEquals(0, agent.contextOverhead(), "compaction replaced the message set; the stale anchor must go");
    }

    @Test
    void abortStopsTheRetryChain() {
        // A retryable error would normally retry, but aborting mid-flight must halt the chain after the first attempt.
        var llm = new FauxLlm();
        var agentRef = new AtomicReference<AgentLoop>();
        llm.onRequest(() -> agentRef.get().abort()).enqueueError("server error 500")
                                        .enqueue(FauxLlm.Response.content("should never run"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").retryBaseDelayMs(1).build();
        agentRef.set(agent);

        agent.prompt("hi"); // aborts during the first request; completes without a further retry
        assertEquals(1, llm.requests().size(), "aborted turn must not retry");
    }

    @Test
    void contextOverflowRemovesPartialAssistantAndRetries() throws Exception {
        var llm = new FauxLlm().enqueueError("maximum context length exceeded")
                                        .enqueue(FauxLlm.Response.content("recovered"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();

        var messages = agent.prompt("hi").get(2, TimeUnit.SECONDS);

        assertEquals(2, messages.size());
        assertEquals("recovered", ((Message.Assistant) messages.get(1)).content());
    }


    @Test
    void manualCompactionFiresCompactionEventAndInsertsSummary() {
        var summarizer = (CompactionManager.SummaryGenerator) (msgs, instr, prev, onPart) -> "summary";
        var config = new AppConfig.CompactionConfig(true, 100, 1);
        var agent = AgentLoop.builder(new FauxLlm(), new ToolRegistry(), "/workspace").summaryGenerator(summarizer)
                                        .compactionConfig(config).contextWindow(1000).build();
        var events = EventRecorder.capture(agent);
        agent.addSystemPrompt("sys");
        agent.sendUserMessage("old");
        agent.sendUserMessage("middle");
        agent.sendUserMessage("recent big " + "x".repeat(4000));

        var result = agent.compactWithResult("compact");

        assertTrue(result.compacted());
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.Compaction));
        assertTrue(agent.messages().stream().anyMatch(m -> m instanceof Message.CompactionSummary));
    }

    @Test
    void maxTurnsTerminationAddsLimitMessage() throws Exception {
        var llm = new FauxLlm();
        for (int i = 0; i < 25; i++) {
            llm.enqueue(FauxLlm.Response.toolCall(new ToolCall("tc" + i, "fake", "{\"value\":\"ok\"}")));
        }
        var registry = new ToolRegistry();
        registry.register(TestTools.echoing("fake"));
        var agent = AgentLoop.builder(llm, registry, "/workspace").maxTurns(25).build();

        var messages = agent.prompt("loop").get(2, TimeUnit.SECONDS);

        var last = (Message.Assistant) messages.get(messages.size() - 1);
        assertTrue(last.content().contains("Max turns"));
    }

    @Test
    void abortSetsClosedAndCompletesResult() throws Exception {
        var llm = new FauxLlm().blockUntilAborted();
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();

        var future = agent.prompt("start");
        Await.until(agent::isBusy);
        agent.close();

        assertTrue(agent.isClosed());
        assertDoesNotThrow(() -> future.get(1, TimeUnit.SECONDS));
    }

    @Test
    void isBusyReflectsActivePrompt() throws Exception {
        var llm = new FauxLlm().blockUntilAborted();
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();

        var future = agent.prompt("start");
        assertTrue(agent.isBusy());
        agent.abort();
        future.get(1, TimeUnit.SECONDS);
        assertFalse(agent.isBusy());
    }

    @Test
    void abortEmitsTurnEndSoEventDrivenFrontendsClearBusy() throws Exception {
        // The TUI keys off the prompt future, but the web learns the turn ended only via the TurnEnd event. Without
        // it, the web Stop button lingers after an interrupt (the turn's normal paths skip TurnEnd once aborted).
        var llm = new FauxLlm().blockUntilAborted();
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();
        var events = EventRecorder.capture(agent);

        var future = agent.prompt("start");
        Await.until(agent::isBusy);
        agent.abort();
        future.get(1, TimeUnit.SECONDS);

        assertFalse(agent.isBusy());
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.TurnEnd),
                                        "abort must emit TurnEnd so the web clears its Stop button / busy state");
    }

    @Test
    void setContextWindowDoesNotThrowForRangeOfValues() {
        var agent = AgentLoop.builder(new FauxLlm(), new ToolRegistry(), "/workspace").build();
        assertDoesNotThrow(() -> agent.setContextWindow(100));
        assertDoesNotThrow(() -> agent.setContextWindow(0));
        assertDoesNotThrow(() -> agent.setContextWindow(((long) Integer.MAX_VALUE) + 1));
    }

    @Test
    void setAutoCompactionEnabledDoesNotThrow() {
        var agent = AgentLoop.builder(new FauxLlm(), new ToolRegistry(), "/workspace").build();
        assertDoesNotThrow(() -> {
            agent.setAutoCompactionEnabled(true);
            agent.setAutoCompactionEnabled(false);
        });
    }

    @Test
    void restoresMessagesAndClearsHistory() {
        var agent = AgentLoop.builder(new FauxLlm(), new ToolRegistry(), "/workspace").build();
        agent.addSystemPrompt("sys");
        agent.sendUserMessage("hi");

        agent.clearMessages();
        assertTrue(agent.messages().isEmpty());

        agent.restoreMessages(List.of(new Message.User("restored")));
        assertEquals(1, agent.messages().size());
    }

    @Test
    void userMessagesReturnsOnlyUserMessages() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content("a1"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();
        agent.prompt("q").get(1, TimeUnit.SECONDS);

        var users = agent.userMessages();
        assertEquals(1, users.size());
        assertEquals("q", users.get(0).content());
    }

    @Test
    void turnStartEventCountsUpEachTurn() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content("a1")).enqueue(FauxLlm.Response.content("a2"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();
        var turnStarts = new AtomicInteger(0);
        agent.addEventListener(e -> {
            if (e instanceof AgentEvent.TurnStart)
                turnStarts.incrementAndGet();
        });

        agent.prompt("q1").get(1, TimeUnit.SECONDS);
        agent.prompt("q2").get(1, TimeUnit.SECONDS);

        assertTrue(turnStarts.get() >= 2);
    }

    @Test
    void assistantMessageWithUsageIsStored() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content(new Usage(3, 5), "hi"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();

        var messages = agent.prompt("hello").get(1, TimeUnit.SECONDS);

        assertEquals("hi", ((Message.Assistant) messages.get(1)).content());
    }

    // ---- resilience: failures reach the extension handlers, the user, and never hang/crash the loop ----

    private static ExtensionRegistry registryWithExceptionHandler(Consumer<Throwable> onException) {
        var registry = new ExtensionRegistry();
        registry.register(new Extension() {
            @Override
            public String name() {
                return "test-exception-handler";
            }

            @Override
            public String description() {
                return "records loop failures";
            }

            @Override
            public void init(ExtensionAPI api) {
                api.registerExceptionHandler(t -> onException.accept(t));
            }
        });
        return registry;
    }

    @Test
    void providerFailureIsDispatchedToExtensionHandlersReportedAndCompletesTheTurn() {
        var captured = new ArrayList<Throwable>();
        var registry = registryWithExceptionHandler(captured::add);
        var llm = new FauxLlm().enqueueError(new RuntimeException("provider rejected the request"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").extensionRegistry(registry).build();
        var events = EventRecorder.capture(agent);

        var future = agent.prompt("hi");

        assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS),
                                        "the turn must complete, not hang");
        assertEquals(1, captured.size(), "the failure must reach the registered exception handler");
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.Error),
                                        "the user is told via AgentEvent.Error");
        assertFalse(agent.isBusy(), "a failed turn must clear busy state");
    }

    @Test
    void synchronousProviderFailureNeitherEscapesPromptNorHangs() {
        var captured = new ArrayList<Throwable>();
        var registry = registryWithExceptionHandler(captured::add);
        // a provider that throws on the calling thread (bad config / NPE building the request) instead of failing async
        var llm = new FauxLlm().throwOnStream(new RuntimeException("synchronous boom"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").extensionRegistry(registry).build();

        var future = assertDoesNotThrow(() -> agent.prompt("hi"), "prompt() must not throw synchronously");

        assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS),
                                        "the turn must complete, not hang");
        assertEquals(1, captured.size());
        assertFalse(agent.isBusy());
    }

    @Test
    void aThrowingExceptionHandlerCannotTurnAFailureIntoACrash() {
        var registry = registryWithExceptionHandler(t -> {
            throw new RuntimeException("misbehaving handler");
        });
        var llm = new FauxLlm().enqueueError(new RuntimeException("provider rejected the request"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").extensionRegistry(registry).build();
        var events = EventRecorder.capture(agent);

        var future = agent.prompt("hi");

        assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.Error), "still reported to the user");
        assertFalse(agent.isBusy());
    }

    @Test
    void stopsAtConfiguredMaxTurnsAndClearsBusy() throws Exception {
        // A turn that keeps calling tools must stop at the configured cap, and that terminal path must clear the busy
        // flag, otherwise isBusy() stays stuck true and wrongly blocks /transcend, /model, etc. ("a turn is running"
        // with nothing to interrupt).
        var llm = new FauxLlm();
        for (int i = 0; i < 6; i++)
            llm.enqueue(FauxLlm.Response.toolCall(new ToolCall("tc" + i, "fake", "{\"value\":\"ok\"}")));
        var registry = new ToolRegistry();
        registry.register(TestTools.echoing("fake"));
        var agent = AgentLoop.builder(llm, registry, "/workspace").maxTurns(3).build();

        var messages = agent.prompt("loop").get(5, TimeUnit.SECONDS);

        assertTrue(messages.stream().anyMatch(
                                        m -> m instanceof Message.Assistant a && a.content().contains("Max turns (3)")),
                                        "loop should stop at the configured cap");
        assertFalse(agent.isBusy(), "isBusy() must clear when the turn cap is hit");
    }

    @Test
    void maxTurnsZeroMeansUnlimited() throws Exception {
        // maxTurns <= 0 disables the cap: a few tool calls then a final answer completes normally (no "Max turns").
        var llm = new FauxLlm().enqueue(FauxLlm.Response.toolCall(new ToolCall("t1", "fake", "{\"value\":\"ok\"}")))
                                        .enqueue(FauxLlm.Response.toolCall(new ToolCall("t2", "fake",
                                                                        "{\"value\":\"ok\"}")))
                                        .enqueue(FauxLlm.Response.content("done"));
        var registry = new ToolRegistry();
        registry.register(TestTools.echoing("fake"));
        var agent = AgentLoop.builder(llm, registry, "/workspace").maxTurns(0).build();

        var messages = agent.prompt("loop").get(5, TimeUnit.SECONDS);

        assertTrue(messages.stream().anyMatch(m -> m instanceof Message.Assistant a && a.content().equals("done")));
        assertFalse(messages.stream().anyMatch(
                                        m -> m instanceof Message.Assistant a && a.content().contains("Max turns")));
        assertFalse(agent.isBusy());
    }

}
