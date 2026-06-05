package ai.pijota.agent;

import ai.pijota.extensions.AgentContext;
import ai.pijota.extensions.ExtensionRegistry;
import ai.pijota.config.AppConfig;
import ai.pijota.llm.LlmService;
import ai.pijota.llm.StreamResult;
import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;
import ai.pijota.tools.ToolRegistry;
import ai.pijota.util.ErrorUtil;
import ai.pijota.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class AgentLoop implements AgentContext {
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final int MAX_TURNS = 25;

    private final LlmService llm;
    private final ToolRegistry toolRegistry;
    private final String cwd;
    private final MessageHistory history;
    private final List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ExtensionRegistry extensionRegistry;
    private final CompactionManager compaction;
    private final List<String> steeringMessages = new ArrayList<>();
    private final List<String> followUpMessages = new ArrayList<>();
    private final ToolExecutor toolExecutor;
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final AtomicReference<CompletableFuture<List<Message>>> activeResult =
        new AtomicReference<>();
    private final AtomicReference<CompletableFuture<StreamResult>> activeStream =
        new AtomicReference<>();
    private volatile boolean messageStreamStarted = false;
    private volatile String lastStreamContent = "";
    private int sessionTurn;

    public static Builder builder(LlmService llm, ToolRegistry toolRegistry, String cwd) {
        return new Builder(llm, toolRegistry, cwd);
    }

    public static final class Builder {
        private final LlmService llm;
        private final ToolRegistry toolRegistry;
        private final String cwd;
        private CompactionManager.SummaryGenerator summaryGenerator;
        private ExtensionRegistry extensionRegistry;
        private int contextWindow;
        private AppConfig.CompactionConfig compactionConfig;

        Builder(LlmService llm, ToolRegistry toolRegistry, String cwd) {
            this.llm = llm; this.toolRegistry = toolRegistry; this.cwd = cwd;
        }

        public Builder summaryGenerator(CompactionManager.SummaryGenerator g) { this.summaryGenerator = g; return this; }
        public Builder extensionRegistry(ExtensionRegistry er) { this.extensionRegistry = er; return this; }
        public Builder contextWindow(int cw) { this.contextWindow = cw; return this; }
        public Builder compactionConfig(AppConfig.CompactionConfig cfg) { this.compactionConfig = cfg; return this; }

        public AgentLoop build() {
            CompactionManager cm;
            if (summaryGenerator != null) {
                cm = contextWindow > 0 || compactionConfig != null
                    ? new CompactionManager(contextWindow, compactionConfig, summaryGenerator)
                    : new CompactionManager(summaryGenerator);
            } else {
                cm = new CompactionManager(null);
            }
            return new AgentLoop(llm, toolRegistry, cwd, cm, extensionRegistry);
        }
    }

    private AgentLoop(LlmService llm, ToolRegistry toolRegistry, String cwd,
                      CompactionManager compaction, ExtensionRegistry extensionRegistry) {
        this.llm = llm; this.toolRegistry = toolRegistry; this.cwd = cwd;
        this.history = new MessageHistory();
        this.compaction = compaction;
        this.extensionRegistry = extensionRegistry;
        this.toolExecutor = new ToolExecutor(toolRegistry, extensionRegistry, cwd, history,
            this::fireEvent, aborted);
    }

    public void addEventListener(Consumer<AgentEvent> l) { listeners.add(l); }
    public void removeEventListener(Consumer<AgentEvent> l) { listeners.remove(l); }
    public LlmService llm() { return llm; }
    public ToolRegistry toolRegistry() { return toolRegistry; }

    public void addSystemPrompt(String s) { history.addSystemPrompt(s); }
    public void replaceSystemPrompt(String content) { history.replaceSystemPrompt(content); }
    public List<Message> messages() { return history.all(); }
    MessageHistory messageHistory() { return history; }
    public void clearMessages() { history.clear(); }
    public void restoreMessages(List<Message> msgs) { history.restore(msgs); }
    public String lastAssistantContent() { return history.lastAssistantContent(); }
    public Optional<String> undoLastUserTurn() { return history.undoLastUserTurn(); }
    public void stripSystemRemindersFromContext() { history.stripSystemReminders(); }
    public List<Message.User> userMessages() { return history.userMessages(); }
    public void followUp(String m) { followUpMessages.add(m); }
    public boolean isBusy() { return activeResult.get() != null; }
    public boolean isClosed() { return aborted.get(); }
    public void close() { abort(); }

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
        return runCompaction(instructions, (msgs, cb, partial) -> compaction.compactNow(msgs, instructions, cb, partial));
    }

    private void runAutoCompaction(String reason) {
        runCompaction(reason, (msgs, cb, partial) -> compaction.checkAndCompact(msgs, cb));
    }

    @FunctionalInterface
    private interface CompactionStrategy {
        CompactionManager.CompactionResult apply(List<Message> messages,
                                                 java.util.function.Consumer<List<Message>> callback,
                                                 java.util.function.BiConsumer<String, String> partialCallback);
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
            if (msg instanceof Message.CompactionSummary cs) return cs.summary();
        }
        return "";
    }

    public void abort() {
        aborted.set(true);
        llm.abort();
        CompletableFuture<StreamResult> stream = activeStream.getAndSet(null);
        if (stream != null) stream.cancel(true);
        toolRegistry.abortRunningTools();
        removeStalePartialAssistant();
        CompletableFuture<List<Message>> result = activeResult.getAndSet(null);
        if (result != null && !result.isDone()) result.complete(new ArrayList<>(history.mutableList()));
    }

    @Override
    public void sendUserMessage(String content) {
        if (isBusy()) {
            sendUserMessage(content, "steer");
        } else {
            history.add(new Message.User(content));
            fireEvent(new AgentEvent.TurnStart(sessionTurn + 1));
        }
    }

    @Override
    public void sendUserMessage(String content, String deliverAs) {
        switch (deliverAs) {
            case "steer" -> steeringMessages.add(content);
            case "followUp" -> followUpMessages.add(content);
            default -> {
                if (isBusy()) steeringMessages.add(content);
                else history.add(new Message.User(content));
            }
        }
    }

    @Override
    public void addCustomMessage(Message.Custom message) {
        history.add(message);
        fireEvent(new AgentEvent.CustomMessage(message));
    }

    private void removeStalePartialAssistant() {
        var msgs = history.mutableList();
        synchronized (msgs) {
            if (msgs.isEmpty()) return;
            var last = msgs.get(msgs.size() - 1);
            if (last instanceof Message.Assistant a
                && a.content().isEmpty()
                && a.toolCalls().isEmpty()
                && (a.thinking() == null || a.thinking().isBlank())) {
                msgs.remove(msgs.size() - 1);
            }
        }
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

    public CompletableFuture<List<Message>> runLoop() {
        var result = new CompletableFuture<List<Message>>();
        activeResult.set(result);
        sessionTurn++;
        runLoopRecursive(result, sessionTurn);
        return result;
    }

    private void runLoopRecursive(CompletableFuture<List<Message>> result, int turn) {
        if (aborted.get()) {
            fireEvent(new AgentEvent.Error(new RuntimeException("Aborted")));
            result.complete(new ArrayList<>(history.mutableList()));
            return;
        }
        if (turn > MAX_TURNS) {
            history.add(new Message.Assistant("[Max turns (" + MAX_TURNS + ") reached. Stopping.]",
                List.of(), null));
            result.complete(new ArrayList<>(history.mutableList()));
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
                CompletableFuture<StreamResult> stream = llm.streamMessages(providerMessages, toolSpecs, (partialContent, partialThinking) -> {
                    if (!isActive(result)) return;
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
                    var updated = new Message.Assistant(partialContent, List.of(), partialThinking);
                    fireEvent(new AgentEvent.AssistantMessage(updated, null));
                    history.set(history.size() - 1, updated.withSource(requestProvider, requestModel));
                });
                activeStream.set(stream);
                return stream;
            })
            .thenAccept(streamResult -> {
                activeStream.getAndSet(null);
                if (!isActive(result)) return;
                fireEvent(new AgentEvent.MessageStreamEnd("assistant"));

                var newToolCalls = streamResult.hasToolCalls()
                    ? streamResult.toolCalls()
                    : List.<ToolCall>of();

                var finalContent = streamResult.content() != null ? streamResult.content() : "";
                var finalThinking = streamResult.thinking() != null && !streamResult.thinking().isBlank()
                    ? streamResult.thinking() : null;
                var assistantMsg = new Message.Assistant(finalContent, newToolCalls, finalThinking)
                    .withSource(requestProvider, requestModel);
                fireEvent(new AgentEvent.AssistantMessage(assistantMsg, streamResult.usage()));
                history.set(history.size() - 1, assistantMsg);

                if (!streamResult.hasToolCalls()) {
                        fireEvent(new AgentEvent.TurnEnd(turn));
                    processFollowUp(result, turn);
                    return;
                }

                toolExecutor.execute(streamResult.toolCalls())
                    .thenAccept(_ -> {
                        if (!isActive(result)) return;
                    fireEvent(new AgentEvent.TurnEnd(turn));
                        runAutoCompaction("auto");
                        runLoopRecursive(result, turn + 1);
                    })
                    .exceptionally(ex -> {
                        if (!isActive(result)) return null;
                        log.warn("Tool execution chain failed", ex);
                        result.completeExceptionally(ex);
                        return null;
                    });
            })
            .exceptionally(ex -> {
                activeStream.set(null);
                if (!isActive(result)) return null;
                if (ErrorUtil.isContextOverflow(ex)) {
                    log.warn("Context overflow, compacting and retrying");
                    history.remove(history.size() - 1);
                    runManualCompaction("overflow — compacted to recover from context limit");
                    runLoopRecursive(result, turn);
                    return null;
                }
                String message = ErrorUtil.message(ex);
                log.warn("LLM streaming error: {}", message);
                fireEvent(new AgentEvent.Error(new RuntimeException(message)));
                result.completeExceptionally(new RuntimeException(message));
                return null;
            });
    }

    private void processSteeringMessages() {
        if (steeringMessages.isEmpty()) return;
        drainAndProcess(steeringMessages);
    }

    private boolean isActive(CompletableFuture<List<Message>> result) {
        return !aborted.get() && activeResult.get() == result;
    }

    private void processFollowUp(CompletableFuture<List<Message>> result, int turn) {
        if (!followUpMessages.isEmpty() && !aborted.get()) {
            drainAndProcess(followUpMessages);
            runLoopRecursive(result, turn + 1);
        } else {
            activeResult.compareAndSet(result, null);
            result.complete(new ArrayList<>(history.mutableList()));
        }
    }

    private void drainAndProcess(List<String> list) {
        for (String msg : List.copyOf(list)) {
            history.add(new Message.User(msg));
        }
        list.clear();
    }

    private void fireEvent(AgentEvent event) {
        for (var l : listeners) {
            try { l.accept(event); }
            catch (Exception e) { log.warn("Event listener error", e); }
        }
    }

    private <T> CompletableFuture<T> retry(Supplier<CompletableFuture<T>> task) {
        return retry(task, 0);
    }

    private <T> CompletableFuture<T> retry(Supplier<CompletableFuture<T>> task, int attempt) {
        return task.get()
            .exceptionallyCompose(ex -> {
                if (attempt >= 2 || !ErrorUtil.isRetryable(ex))
                    return CompletableFuture.failedFuture(ex);
                long defaultDelay = (long) (2000 * Math.pow(2.0, attempt));
                long delay = ErrorUtil.retryAfterDelayMs(ex, defaultDelay);
                log.warn("LLM call failed (attempt {}/{}), retrying in {}ms: {}",
                    attempt + 1, 3, delay, ErrorUtil.message(ex));
                var delayed = new CompletableFuture<T>();
                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                    .execute(() -> retry(task, attempt + 1)
                        .thenAccept(delayed::complete)
                        .exceptionally(e -> { delayed.completeExceptionally(e); return null; }));
                return delayed;
            });
    }
}
