package ai.pijota.agent;

import ai.pijota.extensions.AgentContext;
import ai.pijota.extensions.DeliverAs;
import ai.pijota.extensions.ExtensionRegistry;
import ai.pijota.config.AppConfig;
import ai.pijota.llm.LlmClient;
import ai.pijota.llm.StreamResult;
import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;
import ai.pijota.tools.ToolRegistry;
import ai.pijota.util.ErrorUtil;
import ai.pijota.util.Util;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
    // prompt) concurrently with the turn thread draining them — see sendUserMessage / drainAndProcess.
    private final List<String> steeringMessages = Collections.synchronizedList(new ArrayList<>());
    private final List<String> followUpMessages = Collections.synchronizedList(new ArrayList<>());
    private final ToolExecutor toolExecutor;
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final AtomicReference<CompletableFuture<List<Message>>> activeResult = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<StreamResult>> activeStream = new AtomicReference<>();
    private volatile boolean messageStreamStarted = false;
    private volatile String lastStreamContent = "";
    private int sessionTurn;
    private final long retryBaseDelayMs;
    private final int maxTurns; // per-prompt turn cap; <= 0 means unlimited

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
        return history.all();
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
            // so event-driven frontends (the web Stop button / busy state) learn the turn ended — the TUI relies on the
            // future below, but the web only learns via this event. Consumers treat TurnEnd idempotently.
            fireEvent(new AgentEvent.TurnEnd(sessionTurn));
            result.complete(history.mutableSnapshot());
        }
    }

    @Override
    public void sendUserMessage(String content) {
        if (isBusy()) {
            sendUserMessage(content, DeliverAs.STEER);
        } else {
            history.add(new Message.User(content));
            fireEvent(new AgentEvent.TurnStart(sessionTurn + 1));
        }
    }

    @Override
    public void sendUserMessage(String content, DeliverAs deliverAs) {
        switch (deliverAs) {
            case STEER -> steeringMessages.add(content);
            case FOLLOW_UP -> followUpMessages.add(content);
            case NEXT_TURN -> {
                if (isBusy())
                    steeringMessages.add(content);
                else
                    history.add(new Message.User(content));
            }
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

    public CompletableFuture<List<Message>> prompt(Message.User userMessage) {
        aborted.set(false);
        history.add(userMessage);
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

        processSteeringMessages();

        var requestMessages = history.snapshot();
        String requestProvider = llm.provider();
        String requestModel = llm.model();
        var toolSpecs = toolRegistry.enabledTools();

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
                                            (partialContent, partialThinking) -> {
                                                if (!isActive(result))
                                                    return;
                                                if (!messageStreamStarted) {
                                                    messageStreamStarted = true;
                                                    fireEvent(new AgentEvent.MessageStreamStart("assistant"));
                                                }
                                                String content = partialContent != null ? partialContent : "";
                                                String delta = Util.deltaPrefix(content, lastStreamContent);
                                                lastStreamContent = content;
                                                if (delta != null && !delta.isEmpty()) {
                                                    fireEvent(new AgentEvent.MessageStreamDelta(delta));
                                                }
                                                var updated = new Message.Assistant(partialContent, List.of(),
                                                                                partialThinking);
                                                fireEvent(new AgentEvent.AssistantMessage(updated, null));
                                                history.set(history.size() - 1, updated.withSource(requestProvider,
                                                                                requestModel));
                                            });
            activeStream.set(stream);
            return stream;
        }).thenAccept(streamResult -> {
            activeStream.getAndSet(null);
            if (!isActive(result))
                return;
            fireEvent(new AgentEvent.MessageStreamEnd("assistant"));

            var newToolCalls = streamResult.hasToolCalls() ? streamResult.toolCalls() : List.<ToolCall>of();

            var finalContent = streamResult.content() != null ? streamResult.content() : "";
            var finalThinking = streamResult.thinking() != null && !streamResult.thinking().isBlank()
                                            ? streamResult.thinking()
                                            : null;
            var assistantMsg = new Message.Assistant(finalContent, newToolCalls, finalThinking)
                                            .withSource(requestProvider, requestModel);
            fireEvent(new AgentEvent.AssistantMessage(assistantMsg, streamResult.usage()));
            history.set(history.size() - 1, assistantMsg);

            if (!streamResult.hasToolCalls()) {
                fireEvent(new AgentEvent.TurnEnd(turn));
                processFollowUp(result, turn);
                return;
            }

            toolExecutor.execute(streamResult.toolCalls()).thenAccept(_ -> {
                if (!isActive(result))
                    return;
                fireEvent(new AgentEvent.TurnEnd(turn));
                runAutoCompaction("auto");
                runLoopRecursive(result, turn + 1);
            }).exceptionally(ex -> {
                failTurn(result, ex);
                return null;
            });
        }).exceptionally(ex -> {
            try {
                activeStream.set(null);
                if (!isActive(result))
                    return null;
                if (ErrorUtil.isContextOverflow(ex)) {
                    log.warn("Context overflow, compacting and retrying");
                    history.remove(history.size() - 1);
                    runManualCompaction("overflow — compacted to recover from context limit");
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
        List<String> pending;
        synchronized (list) { // atomic snapshot+clear so a concurrent background add() is never lost or double-read
            pending = List.copyOf(list);
            list.clear();
        }
        for (String msg : pending) {
            history.add(new Message.User(msg));
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
     * future exceptionally so it can never hang. A no-op when the turn was aborted or superseded by a newer one — those
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
    private static final long MAX_RETRY_DELAY_MS = 60_000L;

    private <T> CompletableFuture<T> retry(Supplier<CompletableFuture<T>> task, int attempt) {
        return task.get().exceptionallyCompose(ex -> {
            if (attempt >= MAX_RETRY_ATTEMPTS || !ErrorUtil.isRetryable(ex))
                return CompletableFuture.failedFuture(ex);
            long defaultDelay = (long) (retryBaseDelayMs * Math.pow(2.0, attempt));
            // Honor Retry-After but cap the wait so a long server-requested delay can't stall the turn indefinitely.
            long delay = Math.min(ErrorUtil.retryAfterDelayMs(ex, defaultDelay), MAX_RETRY_DELAY_MS);
            log.warn("LLM call failed (attempt {}/{}), retrying in {}ms: {}", attempt + 1, MAX_RETRY_ATTEMPTS + 1,
                                            delay, ErrorUtil.message(ex));
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
