package org.pijota.agent;

import org.pijota.config.AppConfig;
import org.pijota.extensions.AgentContext;
import org.pijota.extensions.DeliverAs;
import org.pijota.extensions.ExtensionRegistry;
import org.pijota.llm.LlmClient;
import org.pijota.llm.StreamResult;
import org.pijota.messages.Message;
import org.pijota.messages.ToolCall;
import org.pijota.messages.Usage;
import org.pijota.tools.ToolManifest;
import org.pijota.tools.ToolRegistry;
import org.pijota.util.ErrorUtil;
import org.pijota.util.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The conversation: send the history, run whatever tools come back, send the results, repeat until the model stops
 * asking for tools.
 *
 * <p>
 * Everything a turn can do hangs off this -- steering messages arriving mid-turn, compaction when the context fills,
 * abort, and the events each frontend renders.
 */
public final class AgentLoop implements AgentContext {
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private final LlmClient llm;
    private final ToolRegistry toolRegistry;
    private final String cwd;
    private final MessageHistory history;
    private final List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ExtensionRegistry extensionRegistry;
    private final CompactionManager compaction;
    // Synchronized: extensions can inject messages from background threads (e.g. a finished background job, a scheduled
    // prompt) concurrently with the turn thread draining them. See sendUserMessage / drainAndProcess.
    private final List<String> steeringMessages = Collections.synchronizedList(new ArrayList<>());
    private final List<String> followUpMessages = Collections.synchronizedList(new ArrayList<>());
    private final ToolExecutor toolExecutor;
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    /** Consecutive compact-and-retry attempts for one prompt; reset once a request lands. See the overflow path. */
    private final AtomicInteger overflowRecoveries = new AtomicInteger();
    private volatile Consumer<Message.User> userMessageAppender = m -> {};
    private final AtomicReference<CompletableFuture<List<Message>>> activeResult = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<StreamResult>> activeStream = new AtomicReference<>();
    private volatile boolean messageStreamStarted = false;
    private volatile String lastStreamContent = "";
    private int sessionTurn;
    private final long retryBaseDelayMs;
    private final int maxTurns; // per-prompt turn cap; <= 0 means unlimited
    // Real prompt size (from the provider) minus our byte estimate of the same messages: the fixed overhead the
    // estimate misses (system prompt + tool schemas + tokenizer differences). See recordContextAnchor / the session's
    // estimatedContextTokens, which adds it back so the live count tracks reality instead of re-estimating everything.
    private volatile long contextOverhead;
    private volatile boolean overheadAnchored; // true once a real response has measured contextOverhead
    private volatile int anchorEpoch; // history.nonAppendEpoch() when contextOverhead was measured

    public static Builder builder(LlmClient llm, ToolRegistry toolRegistry, String cwd) {
        return new Builder(llm, toolRegistry, cwd);
    }

    public static final class Builder {
        private final LlmClient llm;
        private final ToolRegistry toolRegistry;
        private final String cwd;
        private CompactionManager.SummaryGenerator summaryGenerator;
        private ExtensionRegistry extensionRegistry;
        private int contextWindow;
        private AppConfig.CompactionConfig compactionConfig;
        private long retryBaseDelayMs = 2000;
        private int maxTurns = AppConfig.DEFAULT_MAX_TURNS;

        Builder(LlmClient llm, ToolRegistry toolRegistry, String cwd) {
            this.llm = llm;
            this.toolRegistry = toolRegistry;
            this.cwd = cwd;
        }

        public Builder summaryGenerator(CompactionManager.SummaryGenerator g) {
            this.summaryGenerator = g;
            return this;
        }

        public Builder extensionRegistry(ExtensionRegistry er) {
            this.extensionRegistry = er;
            return this;
        }

        public Builder contextWindow(int cw) {
            this.contextWindow = cw;
            return this;
        }

        public Builder compactionConfig(AppConfig.CompactionConfig cfg) {
            this.compactionConfig = cfg;
            return this;
        }

        /** Base backoff for retryable LLM failures (doubles per attempt); default 2000ms. Tests set this low. */
        public Builder retryBaseDelayMs(long ms) {
            this.retryBaseDelayMs = ms;
            return this;
        }

        /** Per-prompt turn cap; {@code <= 0} means unlimited. Defaults to {@link AppConfig#DEFAULT_MAX_TURNS}. */
        public Builder maxTurns(int n) {
            this.maxTurns = n;
            return this;
        }

        public AgentLoop build() {
            CompactionManager cm;
            if (summaryGenerator != null) {
                cm = contextWindow > 0 || compactionConfig != null
                                                ? new CompactionManager(contextWindow, compactionConfig,
                                                                                summaryGenerator)
                                                : new CompactionManager(summaryGenerator);
            } else {
                cm = new CompactionManager(null);
            }
            return new AgentLoop(llm, toolRegistry, cwd, cm, extensionRegistry, retryBaseDelayMs, maxTurns);
        }
    }

    private AgentLoop(LlmClient llm, ToolRegistry toolRegistry, String cwd, CompactionManager compaction,
                                    ExtensionRegistry extensionRegistry, long retryBaseDelayMs, int maxTurns) {
        this.llm = llm;
        this.toolRegistry = toolRegistry;
        this.cwd = cwd;
        this.history = new MessageHistory();
        this.compaction = compaction;
        this.extensionRegistry = extensionRegistry;
        this.retryBaseDelayMs = retryBaseDelayMs;
        this.maxTurns = maxTurns;
        this.toolExecutor = new ToolExecutor(toolRegistry, extensionRegistry, cwd, history, this::fireEvent, aborted);
        // Always-on diagnostic trace of the event stream (to the log file); first listener so it records before any
        // frontend reacts. Quiet at INFO (turn/tool/usage milestones); DEBUG/TRACE add detail when diagnosing.
        listeners.add(new AgentEventLogger()::onEvent);
    }

    public void addEventListener(Consumer<AgentEvent> l) {
        listeners.add(l);
    }

    public void removeEventListener(Consumer<AgentEvent> l) {
        listeners.remove(l);
    }

    public LlmClient llm() {
        return llm;
    }

    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    public void addSystemPrompt(String s) {
        history.addSystemPrompt(s);
    }

    public void replaceSystemPrompt(String content) {
        history.replaceSystemPrompt(content);
    }

    public List<Message> messages() {
        return history.snapshot();
    }

    /**
     * The gap the message estimate misses (the system prompt is already a message, so this is mainly the tool schemas +
     * tokenizer diff). Measured from real usage once a response arrives; until then it's estimated from the enabled
     * tools' serialized schemas, so a fresh session doesn't undercount by the whole tool overhead.
     */
    public long contextOverhead() {
        // The gap belongs to the messages it was measured against. It survives appends -- the point is that the count
        // grows as a turn streams -- but not a removal or a rewrite, whose share of the undercount went with it.
        boolean stillDescribesTheHistory = overheadAnchored && anchorEpoch == history.nonAppendEpoch();
        return stillDescribesTheHistory ? contextOverhead : unanchoredOverhead();
    }

    /**
     * After a response with real token usage, record how far the byte estimate of the current messages undershoots the
     * provider's actual prompt size. Adding this gap back (see {@code estimatedContextTokens}) keeps the displayed
     * context within tokenizer error of the truth, and lets it grow live as messages are appended.
     */
    private void recordContextAnchor(Usage usage) {
        if (usage != null && usage.inputTokens() > 0) {
            contextOverhead = llm.lastContextTokens() - CompactionManager.estimateTokens(history.snapshot());
            anchorEpoch = history.nonAppendEpoch();
            overheadAnchored = true;
        }
    }

    /**
     * The fixed overhead with nothing measured to anchor against: the tool manifest, which is exactly the bytes the
     * provider is sent for the tool list.
     *
     * <p>
     * {@link ToolManifest#snapshot} already builds and memoizes this for the system prompt, so asking it costs nothing
     * on the poll path and cannot drift from what actually goes on the wire -- a second serializer over the same
     * schemas would need its own invalidation, in lockstep with the one the manifest already has.
     */
    private long unanchoredOverhead() {
        if (toolRegistry.activeTools().isEmpty())
            return 0; // approxTokens rounds up, so an empty manifest ("[]") would otherwise read as 1 token
        try {
            return Util.approxTokens(ToolManifest.snapshot(toolRegistry).json().length());
        } catch (RuntimeException e) {
            // The meter is cosmetic and runs several times a second; an unserializable schema already fails the turn
            // itself, and must not also break the status line.
            return 0;
        }
    }

    /**
     * Drop the anchored overhead, for the one editor {@link MessageHistory#nonAppendEpoch()} cannot see.
     *
     * <p>
     * Everything that edits the conversation through {@code MessageHistory} is counted there, so the anchor invalidates
     * itself. Compaction is the exception: it takes the raw list from {@code mutableList()} and rewrites it in place,
     * outside every method that counts, so it has to say so.
     */
    private void resetContextAnchor() {
        contextOverhead = 0;
        overheadAnchored = false;
    }

    public void clearMessages() {
        history.clear();
    }

    public void restoreMessages(List<Message> msgs) {
        history.restore(msgs);
    }

    public String lastAssistantContent() {
        return history.lastAssistantContent();
    }

    public Optional<String> undoLastUserTurn() {
        return history.undoLastUserTurn();
    }

    public void stripSystemRemindersFromContext() {
        history.stripSystemReminders();
    }

    public List<Message.User> userMessages() {
        return history.userMessages();
    }

    /**
     * What is queued to run after the current turn, newest last.
     *
     * <p>
     * Read rather than mirrored. The TUI used to keep its own copy for the "queued N" hint and the pending preview,
     * which is a second truth that drifts: the copy was cleared at each turn start while the counter beside it was not,
     * so the bar offered a command that had nothing to act on. There is one list, and it is this one.
     */
    public List<String> queuedFollowUps() {
        synchronized (followUpMessages) {
            return List.copyOf(followUpMessages);
        }
    }

    /**
     * Takes the newest queued follow-up back, or empty when there is none left to take.
     *
     * <p>
     * Empty also covers "too late": once {@link #processFollowUp} has drained the list the message is a turn in flight,
     * and the caller must say so rather than reach further back. Handing the text to an editor without removing it here
     * is what made a withdrawn follow-up run anyway.
     */
    public Optional<String> withdrawNewestFollowUp() {
        synchronized (followUpMessages) {
            return followUpMessages.isEmpty()
                                            ? Optional.empty()
                                            : Optional.of(followUpMessages.remove(followUpMessages.size() - 1));
        }
    }

    /** Takes every queued follow-up back at once; for an abort, which cancels them rather than running them. */
    public List<String> drainFollowUps() {
        return drain(followUpMessages);
    }

    /**
     * Queues text a person typed while a turn was running. Duplicates are kept: see {@link #queueGeneratedFollowUp}.
     */
    public void followUp(String m) {
        followUpMessages.add(m);
    }

    public boolean isBusy() {
        return activeResult.get() != null;
    }

    public boolean isClosed() {
        return aborted.get();
    }

    public void close() {
        abort();
    }

    public void compact(String instructions) {
        runManualCompaction(instructions);
    }

    public CompactionManager.CompactionResult compactWithResult(String instructions) {
        return runManualCompaction(instructions);
    }

    public void setAutoCompactionEnabled(boolean enabled) {
        compaction.setEnabled(enabled);
    }

    public void setContextWindow(long contextWindow) {
        if (contextWindow > 0 && contextWindow <= Integer.MAX_VALUE) {
            compaction.setContextWindow((int) contextWindow);
        }
    }

    private CompactionManager.CompactionResult runManualCompaction(String instructions) {
        return runCompaction(instructions,
                                        (msgs, cb, partial) -> compaction.compactNow(msgs, instructions, cb, partial));
    }

    private void runAutoCompaction(String reason) {
        runCompaction(reason, (msgs, cb, partial) -> compaction.checkAndCompact(msgs, llm.lastContextTokens(), cb));
    }

    @FunctionalInterface
    private interface CompactionStrategy {
        CompactionManager.CompactionResult apply(List<Message> messages, Consumer<List<Message>> callback,
                                        BiConsumer<String, String> partialCallback);
    }

    private CompactionManager.CompactionResult runCompaction(String reason, CompactionStrategy strategy) {
        if (extensionRegistry != null)
            extensionRegistry.fireSessionBeforeCompact(false, reason);
        var messages = history.mutableList();
        int sizeBefore = messages.size();
        int estimatedChars = CompactionManager.estimateChars(messages);
        int estimatedTokens = CompactionManager.estimateTokens(messages);
        var result = strategy.apply(messages, compacted -> {
            resetContextAnchor(); // the message set the overhead was anchored to is gone; re-anchor on the next turn
            fireEvent(new AgentEvent.CompactionStreamEnd());
            fireEvent(new AgentEvent.Compaction(compacted, sizeBefore, estimatedChars, estimatedTokens,
                                            compactionSummary(compacted), reason));
        }, (content, thinking) -> fireEvent(new AgentEvent.CompactionStream(content, thinking)));
        if (extensionRegistry != null)
            extensionRegistry.fireSessionCompact();
        return result;
    }

    private static String compactionSummary(List<Message> compacted) {
        for (var msg : compacted) {
            if (msg instanceof Message.CompactionSummary cs)
                return cs.summary();
        }
        return "";
    }

    public void abort() {
        aborted.set(true);
        llm.abort();
        CompletableFuture<StreamResult> stream = activeStream.getAndSet(null);
        if (stream != null)
            stream.cancel(true);
        toolRegistry.abortRunningTools();
        history.removeTrailingEmptyAssistant();
        CompletableFuture<List<Message>> result = activeResult.getAndSet(null);
        if (result != null && !result.isDone()) {
            // The turn's normal terminal paths bail on !isActive once aborted, so they never emit TurnEnd. Emit it here
            // so event-driven frontends (the web Stop button / busy state) learn the turn ended. The TUI relies on the
            // future below, but the web only learns via this event. Consumers treat TurnEnd idempotently.
            fireEvent(new AgentEvent.TurnEnd(sessionTurn));
            result.complete(history.mutableSnapshot());
        }
    }

    @Override
    public void sendUserMessage(String content) {
        sendUserMessage(content, DeliverAs.NEXT_TURN);
    }

    @Override
    public void sendUserMessage(String content, DeliverAs deliverAs) {
        switch (deliverAs) {
            case STEER -> steeringMessages.add(content);
            case FOLLOW_UP -> queueGeneratedFollowUp(content);
            // What the no-argument form means, and now the only place it is written. The two used to be separate
            // bodies that disagreed: this one added the message and stopped, while the default fired TurnStart as
            // well, so a message delivered by the enum the javadoc calls "the default" started no turn. Nothing in
            // the product ever passed NEXT_TURN, so the divergence sat behind a name nobody used.
            case NEXT_TURN -> {
                if (isBusy()) {
                    steeringMessages.add(content);
                } else {
                    addUserMessage(new Message.User(content));
                    fireEvent(new AgentEvent.TurnStart(sessionTurn + 1));
                }
            }
        }
    }

    /**
     * Queues a re-prompt the product generated, such as a goal loop's continuation.
     *
     * <p>
     * Identical text already waiting says nothing the queue is not going to say anyway, so it is not queued twice. When
     * a loop's re-prompts outrun its turns they pile up, and the drain hands the model every copy at once: twenty
     * identical instructions in one request, paid for by the token and counted as twenty iterations. Text the user
     * typed goes through {@link #followUp} instead, which keeps duplicates -- saying something twice can be meant when
     * a person does it.
     */
    private void queueGeneratedFollowUp(String content) {
        synchronized (followUpMessages) {
            if (!followUpMessages.contains(content))
                followUpMessages.add(content);
        }
    }

    @Override
    public void addCustomMessage(Message.Custom message) {
        history.add(message);
        fireEvent(new AgentEvent.CustomMessage(message));
    }

    public void addCompactionAppender(CompactionManager.CompactionAppender appender) {
        compaction.setSessionAppender(appender);
    }

    /**
     * Persists a user message the moment it enters history, whoever put it there.
     *
     * <p>
     * Only the typed-prompt path used to be saved, by the caller, and the four other ways a user message reaches
     * history were not: a follow-up queued while a turn ran, a steering message, and both arms of
     * {@link #sendUserMessage}, which is what extensions call. The model saw those, answered them, and the answer was
     * written -- so a reloaded session showed replies with no question above them. Persisting where the message is
     * added is the only place a fifth path cannot forget.
     */
    public void addUserMessageAppender(Consumer<Message.User> appender) {
        this.userMessageAppender = appender != null ? appender : m -> {};
    }

    private void addUserMessage(Message.User message) {
        history.add(message);
        userMessageAppender.accept(message);
    }

    public CompletableFuture<List<Message>> prompt(Message.User userMessage) {
        aborted.set(false);
        overflowRecoveries.set(0);
        addUserMessage(userMessage);
        return runLoop();
    }

    public CompletableFuture<List<Message>> prompt(String userInput) {
        return prompt(new Message.User(userInput));
    }

    private CompletableFuture<List<Message>> runLoop() {
        var result = new CompletableFuture<List<Message>>();
        activeResult.set(result);
        sessionTurn++;
        runLoopRecursive(result, sessionTurn);
        return result;
    }

    private void runLoopRecursive(CompletableFuture<List<Message>> result, int turn) {
        // Any synchronous failure in a turn (extension hooks, history ops, a provider that throws on the calling
        // thread, compaction) is contained here so it can never escape prompt() or leave the turn future hanging.
        try {
            runTurn(result, turn);
        } catch (Throwable t) {
            failTurn(result, t);
        }
    }

    /**
     * Replaces the placeholder at the tail with the finished assistant message.
     *
     * <p>
     * The tail again, as during streaming: the placeholder added before the request is overwritten in place, so the
     * provider's view of the context stays a growing prefix. Provider and model are the ones the request went out with,
     * so a /model switch landing mid-stream cannot claim a reply it did not write.
     */
    private void settleAssistantMessage(StreamResult streamResult, String requestProvider, String requestModel) {
        var toolCalls = streamResult.hasToolCalls() ? streamResult.toolCalls() : List.<ToolCall>of();
        String content = streamResult.content() != null ? streamResult.content() : "";
        String thinking = streamResult.thinking() != null && !streamResult.thinking().isBlank()
                                        ? streamResult.thinking()
                                        : null;
        var assistantMsg = new Message.Assistant(content, toolCalls, thinking).withSource(requestProvider, requestModel)
                                        .withUsage(streamResult.usage());
        fireEvent(new AgentEvent.AssistantMessage(assistantMsg, streamResult.usage()));
        history.set(history.size() - 1, assistantMsg);
        recordContextAnchor(streamResult.usage());
        overflowRecoveries.set(0); // a request landed, so a later overflow is a fresh one worth compacting for
    }

    /**
     * What the model asked for next: nothing, or a batch of tools.
     *
     * <p>
     * No tool calls is the only way a turn ends, and the only point a queued follow-up is consumed. A batch always
     * forces another step -- the model has to see its own results -- with auto-compaction between the two, which is the
     * one place the context is allowed to stop being a pure prefix.
     */
    private void continueOrFinish(StreamResult streamResult, CompletableFuture<List<Message>> result, int turn) {
        if (!streamResult.hasToolCalls()) {
            fireEvent(new AgentEvent.TurnEnd(turn));
            processFollowUp(result, turn);
            return;
        }
        toolExecutor.execute(streamResult.toolCalls()).thenAccept(ignored -> {
            if (!isActive(result))
                return;
            fireEvent(new AgentEvent.TurnEnd(turn));
            runAutoCompaction("auto"); // checkpoint: compact if the next request would not fit
            runLoopRecursive(result, turn + 1);
        }).exceptionally(ex -> {
            failTurn(result, ex);
            return null;
        });
    }

    /**
     * Publishes one partial response while the model is still talking: the new text as a delta, and the assistant
     * message at the tail of the history rewritten to match.
     *
     * <p>
     * Only ever the tail. Every mid-turn write here is an overwrite of the placeholder added just before the request,
     * which is what keeps the provider's view of the context a growing prefix and its KV cache valid -- rewriting
     * anything earlier would invalidate everything after it.
     *
     * <p>
     * The provider and model are the ones the request was sent with, passed in rather than read from {@code llm}: a
     * /model switch landing mid-stream would otherwise attribute the tail of this reply to the model that did not write
     * it.
     */
    private void publishPartial(CompletableFuture<List<Message>> result, String requestProvider, String requestModel,
                                    String partialContent, String partialThinking) {
        if (!isActive(result))
            return;
        if (!messageStreamStarted) {
            messageStreamStarted = true;
            fireEvent(new AgentEvent.MessageStreamStart("assistant"));
        }
        String content = partialContent != null ? partialContent : "";
        String delta = Util.deltaPrefix(content, lastStreamContent);
        lastStreamContent = content;
        if (delta != null && !delta.isEmpty())
            fireEvent(new AgentEvent.MessageStreamDelta(delta));
        var updated = new Message.Assistant(partialContent, List.of(), partialThinking);
        fireEvent(new AgentEvent.AssistantMessage(updated, null));
        history.set(history.size() - 1, updated.withSource(requestProvider, requestModel));
    }

    private void runTurn(CompletableFuture<List<Message>> result, int turn) {
        if (aborted.get()) {
            fireEvent(new AgentEvent.Error(new RuntimeException("Aborted")));
            completeTurn(result, history.mutableSnapshot());
            return;
        }
        if (maxTurns > 0 && turn > maxTurns) {
            history.add(new Message.Assistant("[Max turns (" + maxTurns + ") reached. Stopping.]", List.of(), null));
            completeTurn(result, history.mutableSnapshot());
            return;
        }

        fireEvent(new AgentEvent.TurnStart(turn));

        // Checkpoint (pi harness vocabulary): between steps, queued steering is consumed here and auto-compaction runs
        // after the tool batch below. Both append at the context tail, keeping the provider prefix (KV cache) stable;
        // compaction is the one deliberate exception. Follow-ups are consumed only when the model would stop.
        processSteeringMessages();

        var requestMessages = history.snapshot();
        String requestProvider = llm.provider();
        String requestModel = llm.model();
        var toolSpecs = toolRegistry.activeTools();

        var partialMsg = new Message.Assistant("", List.of(), null);
        history.add(partialMsg);
        fireEvent(new AgentEvent.AssistantMessage(partialMsg, null));
        messageStreamStarted = false;
        lastStreamContent = "";

        retry(() -> {
            var providerMessages = new ArrayList<>(requestMessages);
            if (extensionRegistry != null)
                extensionRegistry.fireBeforeProviderRequest(providerMessages);
            CompletableFuture<StreamResult> stream = llm.streamMessages(providerMessages, toolSpecs,
                                            (partialContent, partialThinking) -> publishPartial(result, requestProvider,
                                                                            requestModel, partialContent,
                                                                            partialThinking),
                                            llm.thinkingLevel(), (toolName, chars) -> {
                                                if (isActive(result))
                                                    fireEvent(new AgentEvent.ToolCallProgress(toolName, chars));
                                            });
            activeStream.set(stream);
            return stream;
        }).thenAccept(streamResult -> {
            activeStream.getAndSet(null);
            if (!isActive(result))
                return;
            fireEvent(new AgentEvent.MessageStreamEnd("assistant"));
            settleAssistantMessage(streamResult, requestProvider, requestModel);
            continueOrFinish(streamResult, result, turn);
        }).exceptionally(ex -> {
            try {
                activeStream.set(null);
                if (!isActive(result))
                    return null;
                if (ErrorUtil.isContextOverflow(ex)) {
                    // Compaction buys room by dropping history, so it needs history to drop. When the system prompt and
                    // the tool schemas are over the limit on their own, the retry rebuilds a request the server rejects
                    // for the same reason, and the placeholder added per attempt holds the size steady, so this never
                    // terminates. Twice without the request landing means compaction is not the fix. The server's own
                    // wording rides along as the cause: failTurn logs that, and shows the actionable text.
                    if (overflowRecoveries.incrementAndGet() > 1) {
                        failTurn(result, new IllegalStateException(ErrorUtil.CONTEXT_FLOOR_HINT, ErrorUtil.unwrap(ex)));
                        return null;
                    }
                    log.warn("Context overflow, compacting and retrying");
                    history.remove(history.size() - 1);
                    runManualCompaction("overflow: compacted to recover from context limit");
                    runLoopRecursive(result, turn);
                    return null;
                }
                failTurn(result, ex);
            } catch (Throwable t) {
                failTurn(result, t); // even the recovery path must not leave the turn hanging
            }
            return null;
        });
    }

    private void processSteeringMessages() {
        if (steeringMessages.isEmpty())
            return;
        drainAndProcess(steeringMessages);
    }

    private boolean isActive(CompletableFuture<List<Message>> result) {
        return !aborted.get() && activeResult.get() == result;
    }

    /**
     * Completes a turn future and clears the busy flag together, so no terminal path can leave {@link #isBusy()} stuck
     * true (which would wrongly block /transcend, /model, etc. with "a turn is running" when nothing is).
     */
    private void completeTurn(CompletableFuture<List<Message>> result, List<Message> snapshot) {
        activeResult.compareAndSet(result, null);
        result.complete(snapshot);
    }

    private void processFollowUp(CompletableFuture<List<Message>> result, int turn) {
        if (!followUpMessages.isEmpty() && !aborted.get()) {
            drainAndProcess(followUpMessages);
            runLoopRecursive(result, turn + 1);
        } else {
            completeTurn(result, history.mutableSnapshot());
        }
    }

    private void drainAndProcess(List<String> list) {
        for (String msg : drain(list)) {
            addUserMessage(new Message.User(msg));
        }
    }

    /** Atomic snapshot+clear, so a concurrent background add() is never lost or double-read. */
    private static List<String> drain(List<String> list) {
        synchronized (list) {
            var pending = List.copyOf(list);
            list.clear();
            return pending;
        }
    }

    private void fireEvent(AgentEvent event) {
        for (var l : listeners) {
            try {
                l.accept(event);
            } catch (Exception e) {
                log.warn("Event listener error", e);
            }
        }
    }

    /**
     * Terminal handler for a failed turn: hands the cause to the extension exception handlers
     * ({@code registerExceptionHandler}), reports it to the user via {@link AgentEvent.Error}, and completes the turn
     * future exceptionally so it can never hang. A no-op when the turn was aborted or superseded by a newer one; those
     * paths own completion. Resilient: a misbehaving handler or listener can't turn a failure into a crash.
     */
    private void failTurn(CompletableFuture<List<Message>> result, Throwable ex) {
        activeStream.set(null);
        if (!isActive(result))
            return;
        Throwable cause = ErrorUtil.unwrap(ex);
        String message = ErrorUtil.message(cause);
        log.warn("Agent turn failed: {}", message, cause);
        if (extensionRegistry != null) {
            try {
                extensionRegistry.dispatchException(cause);
            } catch (Exception e) {
                log.warn("Exception handler dispatch failed", e);
            }
        }
        fireEvent(new AgentEvent.Error(new RuntimeException(message)));
        activeResult.compareAndSet(result, null);
        result.completeExceptionally(new RuntimeException(message));
    }

    /** Signals that the conversation history was replaced wholesale (load/new/undo/fork) so frontends can re-render. */
    public void notifyHistoryChanged(String reason) {
        fireEvent(new AgentEvent.HistoryChanged(reason));
    }

    private <T> CompletableFuture<T> retry(Supplier<CompletableFuture<T>> task) {
        return retry(task, 0);
    }

    private static final int MAX_RETRY_ATTEMPTS = 5;

    private <T> CompletableFuture<T> retry(Supplier<CompletableFuture<T>> task, int attempt) {
        return task.get().exceptionallyCompose(ex -> {
            // Stop retrying once the user aborts (ESC); don't keep hammering the provider through a cancelled turn.
            if (attempt >= MAX_RETRY_ATTEMPTS || aborted.get() || !ErrorUtil.isRetryable(ex))
                return CompletableFuture.failedFuture(ex);
            // Exponential backoff, capped. (We don't honor a server Retry-After header: langchain4j drops response
            // headers (HttpException carries only a status code), so there is no header to read.)
            long delay = ErrorUtil.backoffMs(retryBaseDelayMs, attempt, ErrorUtil.MAX_BACKOFF_MS);
            // An event, not a log line: the wait is otherwise indistinguishable from the model thinking, and the
            // always-on AgentEventLogger writes the same warning to the log file from the event.
            fireEvent(new AgentEvent.Retry(attempt + 1, MAX_RETRY_ATTEMPTS + 1, delay, ErrorUtil.message(ex)));
            var delayed = new CompletableFuture<T>();
            CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> retry(task, attempt + 1)
                                            .thenAccept(delayed::complete).exceptionally(e -> {
                                                delayed.completeExceptionally(e);
                                                return null;
                                            }));
            return delayed;
        });
    }
}
