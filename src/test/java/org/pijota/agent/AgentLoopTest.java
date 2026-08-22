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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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

    /**
     * DeliverAs.NEXT_TURN is documented as the default, and the no-argument send is how the default is reached. They
     * were two bodies that disagreed -- only one of them fired TurnStart -- and nothing in the product passed the enum,
     * so the divergence hid behind a name nobody used.
     */
    @Test
    void theDefaultDeliveryMeansTheSameThingSpeltEitherWay() throws Exception {
        for (boolean viaEnum : new boolean[] {false, true}) {
            var llm = new FauxLlm().enqueue(FauxLlm.Response.content("ok"));
            var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();
            var starts = new java.util.concurrent.atomic.AtomicInteger();
            agent.addEventListener(e -> {
                if (e instanceof AgentEvent.TurnStart)
                    starts.incrementAndGet();
            });

            if (viaEnum)
                agent.sendUserMessage("hello", DeliverAs.NEXT_TURN);
            else
                agent.sendUserMessage("hello");

            assertEquals(1, starts.get(), "a turn starts either way (viaEnum=" + viaEnum + ")");
            assertEquals(List.of("hello"), agent.userMessages().stream().map(Message.User::content).toList());
        }
    }

    /**
     * A loop whose re-prompts outrun its turns queued one copy per idle. The drain hands the model everything at once,
     * so the request carried the same sentence twenty times over -- paid for by the token, and counted as twenty
     * iterations of a goal that had been told once.
     */
    @Test
    void anIdenticalFollowUpAlreadyWaitingIsNotQueuedTwice() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content("first")).enqueue(FauxLlm.Response.content("second"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();

        for (int i = 0; i < 20; i++)
            agent.sendUserMessage("keep working toward the objective", DeliverAs.FOLLOW_UP);
        var messages = agent.prompt("hi").get(2, TimeUnit.SECONDS);

        long copies = messages.stream().filter(m -> m instanceof Message.User u
                                        && u.content().equals("keep working toward the objective")).count();
        assertEquals(1, copies, "one instruction, said once: " + messages.size() + " messages");
    }

    /** Different follow-ups are all distinct instructions and every one of them still has to arrive. */
    @Test
    void followUpsThatDifferAreAllKept() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content("first")).enqueue(FauxLlm.Response.content("second"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();

        agent.sendUserMessage("job bg_1 finished", DeliverAs.FOLLOW_UP);
        agent.sendUserMessage("job bg_2 failed", DeliverAs.FOLLOW_UP);
        var messages = agent.prompt("hi").get(2, TimeUnit.SECONDS);

        var said = messages.stream().filter(m -> m instanceof Message.User).map(m -> ((Message.User) m).content())
                                        .toList();
        assertTrue(said.contains("job bg_1 finished"), said.toString());
        assertTrue(said.contains("job bg_2 failed"), said.toString());
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

    /**
     * The TUI used to mirror this list to draw "queued N" and to back /dequeue, and the copy drifted: it was cleared at
     * each turn start while the counter beside it was not, so the bar offered a command with nothing to act on. Worse,
     * the mirror was all /dequeue removed -- the agent still held the message and ran it anyway. One list.
     */
    @Test
    void aQueuedFollowUpCanBeTakenBackBeforeItRuns() throws Exception {
        var agent = AgentLoop.builder(new FauxLlm(), new ToolRegistry(), "/workspace").build();

        agent.followUp("first");
        agent.followUp("second");
        assertEquals(List.of("first", "second"), agent.queuedFollowUps());

        assertEquals("second", agent.withdrawNewestFollowUp().orElseThrow(), "newest first");
        assertEquals(List.of("first"), agent.queuedFollowUps(), "and it is gone from the queue, not just reported");

        assertEquals("first", agent.withdrawNewestFollowUp().orElseThrow());
        assertTrue(agent.withdrawNewestFollowUp().isEmpty(), "nothing left to take back");
        assertTrue(agent.queuedFollowUps().isEmpty());
    }

    /**
     * A withdrawn follow-up must not run. Before, /dequeue popped a UI mirror and handed the text to the editor while
     * this list still held it, so the message arrived twice: once from the queue, once when the user pressed Enter.
     */
    @Test
    void aWithdrawnFollowUpDoesNotRun() throws Exception {
        var llm = new FauxLlm().enqueue(FauxLlm.Response.content("answer"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();

        agent.followUp("do not run me");
        assertEquals("do not run me", agent.withdrawNewestFollowUp().orElseThrow());
        var messages = agent.prompt("hi").get(2, TimeUnit.SECONDS);

        assertTrue(messages.stream().noneMatch(
                                        m -> m instanceof Message.User u && u.content().contains("do not run me")),
                                        "it was taken back: " + messages);
        assertEquals(1, llm.requests().size(), "and no extra turn ran for it");
    }

    /** Abort cancels queued follow-ups rather than leaving them to run on the next prompt. */
    @Test
    void drainingReturnsEveryQueuedFollowUpAndEmptiesTheQueue() {
        var agent = AgentLoop.builder(new FauxLlm(), new ToolRegistry(), "/workspace").build();
        agent.followUp("a");
        agent.followUp("b");

        assertEquals(List.of("a", "b"), agent.drainFollowUps());
        assertTrue(agent.queuedFollowUps().isEmpty(), "drained means gone, or an aborted turn runs them later");
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

    /**
     * A backoff nobody announces is indistinguishable from the model thinking. A down local server bought 62 seconds of
     * silence in print mode and 62 seconds of working animation in the TUI, because the retry loop wrote only to the
     * log file. Every frontend renders from events, so the wait has to be one.
     */
    @Test
    void everyRetryIsAnnouncedAsAnEvent() throws Exception {
        var llm = new FauxLlm().enqueueError("connection refused").enqueueError("connection refused")
                                        .enqueue(FauxLlm.Response.content("ok"));
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").retryBaseDelayMs(1).build();
        var events = EventRecorder.capture(agent);

        agent.prompt("hi").get(2, TimeUnit.SECONDS);

        var retries = events.stream().filter(e -> e instanceof AgentEvent.Retry).map(e -> (AgentEvent.Retry) e)
                                        .toList();
        assertEquals(2, retries.size(), "one per backoff, so the user sees each wait: " + retries);
        assertEquals(1, retries.get(0).attempt());
        assertEquals(2, retries.get(1).attempt(), "attempts count up, so 2/6 reads as progress not a loop");
        assertTrue(retries.get(0).delayMs() > 0, "the wait has to be nameable to be worth announcing");
        assertTrue(retries.get(0).message().toLowerCase().contains("connection refused"),
                                        "and it has to say why: " + retries.get(0).message());
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

    /**
     * An agent whose overhead is anchored well above baseline: a large message set, then a response reporting a prompt
     * far bigger than the byte estimate. Shared by the anchor tests, which differ only in how they then change the
     * history.
     */
    private static AgentLoop anchoredAgent() throws Exception {
        var llm = new FauxLlm();
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();
        assertEquals(0, agent.contextOverhead(), "no tools, nothing anchored yet");
        agent.addSystemPrompt("sys");
        llm.enqueue(FauxLlm.Response.content(new Usage(50_000, 10), "ok"));
        agent.prompt("a big turn " + "x".repeat(4000)).get(2, TimeUnit.SECONDS);
        assertTrue(agent.contextOverhead() > 0, "a real response anchors the overhead");
        return agent;
    }

    /**
     * The anchor used to be dropped by whichever callers remembered to say so, which is why /undo kept a stale one. It
     * now invalidates itself: MessageHistory counts every change that is not an append, and the anchor is honoured only
     * while that count matches. This is the path the caller-announced version missed entirely -- a reminder strip runs
     * on every submit and deletes messages outright, and nothing notifies anyone.
     */
    @Test
    void strippingASystemReminderDropsTheAnchorItInvalidates() throws Exception {
        var agent = anchoredAgent();
        agent.sendUserMessage("keep me <system-reminder>and drop this</system-reminder>");

        agent.stripSystemRemindersFromContext();

        assertEquals(0, agent.contextOverhead(), "the stripped text took its share of the gap with it");
    }

    /** Appends must NOT invalidate it: the meter growing as a turn streams is the whole reason the anchor exists. */
    @Test
    void appendingKeepsTheAnchor() throws Exception {
        var agent = anchoredAgent();
        long anchored = agent.contextOverhead();

        agent.sendUserMessage("another message");
        agent.addCustomMessage(new Message.Custom("note", "value"));

        assertEquals(anchored, agent.contextOverhead(), "adding messages must not throw the anchor away");
    }

    /**
     * /undo edits the list in place -- it never calls clearMessages or restoreMessages -- so it slipped past the resets
     * those two carry and kept an anchor measured against the turn it had just removed. The meter then read high by
     * that turn's whole underestimate, which is enough to trip auto-compaction on a conversation that just got smaller.
     * Reaching it through the session, because notifyHistoryChanged is what the undo path actually goes through.
     */
    @Test
    void undoDropsTheAnchorMeasuredAgainstTheUndoneTurn() throws Exception {
        var llm = new FauxLlm();
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();
        long baseline = agent.contextOverhead(); // no tools → 0

        agent.addSystemPrompt("sys");
        llm.enqueue(FauxLlm.Response.content(new Usage(50_000, 10), "ok"));
        agent.prompt("a big turn " + "x".repeat(4000)).get(2, TimeUnit.SECONDS);
        assertTrue(agent.contextOverhead() > baseline, "a real response anchors the overhead above baseline");

        agent.undoLastUserTurn();
        agent.notifyHistoryChanged("undo"); // what PijotaSession.undoLastUserTurn does on success

        assertEquals(baseline, agent.contextOverhead(), "the undone turn took its share of the gap with it");
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

    /**
     * The /compact-vs-prompt race: compaction rewrites history in place, so while the summarizer runs the loop must
     * read as busy (every frontend's prompt/command gate keys off isBusy) and refuse a second compaction outright.
     */
    @Test
    void compactionMarksTheLoopBusyAndRefusesOverlap() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var summarizer = (CompactionManager.SummaryGenerator) (msgs, instr, prev, onPart) -> {
            entered.countDown();
            try {
                // Bounded so a broken overlap guard fails the test instead of deadlocking it.
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "summary";
        };
        var config = new AppConfig.CompactionConfig(true, 100, 1);
        var agent = AgentLoop.builder(new FauxLlm(), new ToolRegistry(), "/workspace").summaryGenerator(summarizer)
                                        .compactionConfig(config).contextWindow(1000).build();
        agent.addSystemPrompt("sys");
        agent.sendUserMessage("old");
        agent.sendUserMessage("middle");
        agent.sendUserMessage("recent big " + "x".repeat(4000));

        var done = CompletableFuture.supplyAsync(() -> agent.compactWithResult("compact"));
        assertTrue(entered.await(2, TimeUnit.SECONDS), "summarizer should be running");

        assertTrue(agent.isBusy(), "a running compaction must read as busy so frontend gates cover it");
        assertFalse(agent.compactWithResult("compact again").compacted(),
                                        "a second compaction must not start while one is running");

        release.countDown();
        assertTrue(done.get(2, TimeUnit.SECONDS).compacted());
        assertFalse(agent.isBusy(), "busy clears when compaction finishes");
    }

    /** The other arm: /compact arriving while a turn is in flight must not rewrite the history under it. */
    @Test
    void compactionRefusesWhileATurnRuns() throws Exception {
        var summarizer = (CompactionManager.SummaryGenerator) (msgs, instr, prev, onPart) -> "summary";
        var config = new AppConfig.CompactionConfig(true, 100, 1);
        var llm = new FauxLlm().blockUntilAborted();
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").summaryGenerator(summarizer)
                                        .compactionConfig(config).contextWindow(1000).build();
        // Enough history that compaction WOULD succeed if the busy guard did not refuse it.
        agent.addSystemPrompt("sys");
        agent.sendUserMessage("old");
        agent.sendUserMessage("middle");
        agent.sendUserMessage("recent big " + "x".repeat(4000));

        var future = agent.prompt("hi");
        Await.until(agent::isBusy);

        assertFalse(agent.compactWithResult("compact").compacted(), "compaction must refuse while a turn is running");

        agent.abort();
        future.get(1, TimeUnit.SECONDS);
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

    /**
     * Overflow recovery drops history to make room, so it only works when there is history to drop. A local server
     * given a context too small for the system prompt and the tool schemas rejects every attempt identically, and the
     * empty assistant placeholder added per attempt keeps the history size steady, so the compact-and-retry path used
     * to spin against the server forever. Two overflows without a request landing means compaction is not the fix.
     */
    @Test
    void overflowThatCompactionCannotFixStopsInsteadOfRetryingForever() {
        var llm = new FauxLlm();
        for (int i = 0; i < 8; i++)
            llm.enqueueError("Invalid argument: max_tokens must be non-negative");
        var agent = AgentLoop.builder(llm, new ToolRegistry(), "/workspace").build();

        var thrown = assertThrows(ExecutionException.class, () -> agent.prompt("hi").get(5, TimeUnit.SECONDS));

        assertEquals(2, llm.requests().size(), "one compact-and-retry, then give up; got " + llm.requests().size());
        assertTrue(thrown.getCause().getMessage().contains("compacting cannot help"),
                                        "the message has to say why waiting will not help: " + thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("--no-builtin-tools"),
                                        "and name a way out: " + thrown.getCause().getMessage());
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
