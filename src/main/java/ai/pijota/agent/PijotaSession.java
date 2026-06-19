package ai.pijota.agent;

import ai.pijota.config.AppConfig;
import ai.pijota.extensions.ExtensionRegistry;
import ai.pijota.extensions.task.SubAgent;
import ai.pijota.llm.LlmClient;
import ai.pijota.llm.ThinkingLevel;
import ai.pijota.messages.Message;
import ai.pijota.tools.ToolManifest;
import ai.pijota.tools.ToolRegistry;
import ai.pijota.util.SelfRespawn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class PijotaSession implements AutoCloseable {

    private final AgentLoop agentLoop;
    private final SessionManager sessionManager;
    private final ToolRegistry toolRegistry;
    private volatile Path sessionFile;
    private final ExtensionRegistry extensionRegistry;
    private final String baseSystemPrompt;
    private volatile String sessionSystemPrompt;
    private final String cwd;
    private boolean disableSession;
    private volatile String sessionWarning;
    private final long initialContextWindow;

    public PijotaSession(LlmClient llm, ToolRegistry toolRegistry, SessionManager sessionManager,
                                    ExtensionRegistry extensionRegistry, String systemPrompt, String cwd,
                                    boolean noSession, int contextWindow, AppConfig.CompactionConfig compactionConfig)
                                    throws IOException {
        this.initialContextWindow = contextWindow;
        this.sessionManager = sessionManager;
        this.toolRegistry = toolRegistry;
        this.extensionRegistry = extensionRegistry;
        this.baseSystemPrompt = systemPrompt;
        this.sessionSystemPrompt = systemPrompt;
        this.cwd = cwd;
        this.disableSession = noSession;
        this.sessionFile = noSession ? null : sessionManager.createSession(cwd);
        SelfRespawn.setSessionFile(this.sessionFile);
        // Compaction summaries go through the LlmClient (default: no summarizer → degraded heuristic). An optional
        // "summarize" sub-agent can override the model/system-prompt; null fields fall back to the main model.
        CompactionManager.SummaryGenerator summaryGen = (msgs, instr, prev, onPart) -> {
            var summaryAgent = findSummarizeAgent(cwd);
            return llm.summarize(msgs, instr, prev, onPart, summaryAgent != null ? summaryAgent.model() : null,
                                            summaryAgent != null ? summaryAgent.systemPrompt() : null);
        };
        this.agentLoop = AgentLoop.builder(llm, toolRegistry, cwd).summaryGenerator(summaryGen)
                                        .extensionRegistry(extensionRegistry).contextWindow(contextWindow)
                                        .compactionConfig(compactionConfig).build();

        this.agentLoop.addCompactionAppender((summary, count, firstKeptId) -> {
            Path file = this.sessionFile;
            if (!disableSession && file != null) {
                try {
                    sessionManager.appendCompaction(file, summary, count, firstKeptId);
                } catch (IOException e) {
                    rememberSessionWarning("compaction save", e);
                }
            }
        });
        this.agentLoop.addSystemPrompt(systemPrompt);

        this.agentLoop.addEventListener(event -> {
            switch (event) {
                case AgentEvent.AssistantMessage(Message.Assistant msg, var usage) -> {
                    if (usage != null) {
                        Path file = this.sessionFile;
                        if (!disableSession && file != null) {
                            try {
                                sessionManager.appendMessage(file, msg);
                            } catch (IOException e) {
                                rememberSessionWarning("assistant message save", e);
                            }
                        }
                    }
                }
                case AgentEvent.ToolResult(Message.ToolResult msg) -> {
                    Path file = this.sessionFile;
                    if (!disableSession && file != null) {
                        try {
                            sessionManager.appendMessage(file, msg);
                        } catch (IOException e) {
                            rememberSessionWarning("tool result save", e);
                        }
                    }
                }
                case AgentEvent.CustomMessage(Message.Custom msg) -> {
                    Path file = this.sessionFile;
                    if (!disableSession && file != null) {
                        try {
                            sessionManager.appendMessage(file, msg);
                        } catch (IOException e) {
                            rememberSessionWarning("custom message save", e);
                        }
                    }
                }
                default -> {}
            }
        });
        this.agentLoop.addEventListener(extensionRegistry::onAgentEvent);
    }

    public CompletableFuture<List<Message>> prompt(String userInput) {
        String filtered = extensionRegistry.filterInput(userInput);
        var userMessage = new Message.User(filtered);
        Path file = this.sessionFile;
        if (!disableSession && file != null) {
            try {
                sessionManager.appendMessage(file, userMessage);
            } catch (IOException e) {
                rememberSessionWarning("user message save", e);
            }
        }
        String effectivePrompt = extensionRegistry.fireBeforeAgentStart(agentLoop.messages(), sessionSystemPrompt);
        agentLoop.replaceSystemPrompt(effectivePrompt);
        extensionRegistry.setSystemPrompt(effectivePrompt);
        extensionRegistry.fireAgentStart();
        return agentLoop.prompt(userMessage).thenApply(messages -> {
            extensionRegistry.fireAfterAgentEnd();
            SelfRespawn.maybeRespawn();
            String err = SelfRespawn.lastRespawnError();
            if (err != null)
                rememberSessionWarning("transcend", new IOException(err));
            return messages;
        });
    }

    // -- Session management --

    public AgentLoop agentLoop() {
        return agentLoop;
    }

    public ExtensionRegistry extensionRegistry() {
        return extensionRegistry;
    }

    public Path sessionFile() {
        return sessionFile;
    }

    public List<Message> messages() {
        return agentLoop.messages();
    }

    public List<Message.User> userMessages() {
        return agentLoop.userMessages();
    }

    public String lastAssistantContent() {
        return agentLoop.lastAssistantContent();
    }

    public Optional<String> undoLastUserTurn() {
        Optional<String> undone = agentLoop.undoLastUserTurn();
        undone.ifPresent(ignored -> agentLoop.notifyHistoryChanged("undo"));
        return undone;
    }

    public void abort() {
        agentLoop.abort();
    }

    public void followUp(String message) {
        agentLoop.followUp(message);
    }

    public boolean isBusy() {
        return agentLoop.isBusy();
    }

    public void stripSystemRemindersFromContext() {
        agentLoop.stripSystemRemindersFromContext();
    }

    public void fireUserBash(String command, boolean excludeFromContext) {
        extensionRegistry.fireUserBash(command, excludeFromContext);
    }

    public CompactionManager.CompactionResult compact(String instructions) {
        return agentLoop.compactWithResult(instructions);
    }

    public void setAutoCompactionEnabled(boolean enabled) {
        agentLoop.setAutoCompactionEnabled(enabled);
    }

    public void setContextWindow(long contextWindow) {
        agentLoop.setContextWindow(contextWindow);
    }

    public void setThinkingLevel(ThinkingLevel level) {
        setThinkingLevel(level, true);
    }

    public void restoreThinkingLevel(ThinkingLevel level) {
        setThinkingLevel(level, false);
    }

    private void setThinkingLevel(ThinkingLevel level, boolean persist) {
        ThinkingLevel effective = level != null ? level : ThinkingLevel.OFF;
        agentLoop.llm().setThinkingLevel(effective);
        Path file = this.sessionFile;
        if (persist && !disableSession && file != null) {
            try {
                sessionManager.appendThinkingLevelChange(file, effective.name());
            } catch (IOException e) {
                rememberSessionWarning("thinking level save", e);
            }
        }
    }

    public void switchModel(String provider, String model, String apiKey, long contextWindow) {
        switchModel(provider, model, apiKey, contextWindow, true);
    }

    public void restoreModel(String provider, String model, String apiKey, long contextWindow) {
        switchModel(provider, model, apiKey, contextWindow, false);
    }

    private void switchModel(String provider, String model, String apiKey, long contextWindow, boolean persist) {
        agentLoop.llm().setProviderAndModel(provider, model, apiKey);
        setContextWindow(contextWindow);
        Path file = this.sessionFile;
        if (persist && !disableSession && file != null) {
            try {
                sessionManager.appendModelChange(file, provider, model, contextWindow);
                writeCacheMetadata(provider, model, sessionSystemPrompt);
            } catch (IOException e) {
                rememberSessionWarning("model change save", e);
            }
        }
    }

    public SessionManager.ModelSelection latestModelSelection() throws IOException {
        return sessionManager.latestModelSelection(sessionFile);
    }

    public ThinkingLevel latestThinkingLevel() throws IOException {
        String value = sessionManager.latestThinkingLevel(sessionFile);
        if (value == null || value.isBlank())
            return null;
        try {
            return ThinkingLevel.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void addEventListener(Consumer<AgentEvent> listener) {
        agentLoop.addEventListener(listener);
    }

    public List<Path> listSessions() {
        return sessionManager.listSessions(cwd);
    }

    public List<Path> resumableSessions() {
        Path current = sessionFile;
        return sessionManager.listSessions(cwd).stream().filter(path -> current == null || !path.equals(current))
                                        .toList();
    }

    public Path resolveSession(String spec) throws IOException {
        return sessionManager.resolveSession(cwd, spec);
    }

    public void saveMessages(Path file) throws IOException {
        sessionManager.saveMessages(file, agentLoop.messages(), cwd);
    }

    public List<Map<String, Object>> branchEntries() throws IOException {
        return sessionFile != null ? sessionManager.getBranchEntries(sessionFile) : List.of();
    }

    public List<Map<String, Object>> treeEntries() throws IOException {
        return sessionFile != null ? sessionManager.getTreeEntries(sessionFile) : List.of();
    }

    public String leafId() {
        return sessionManager.leafId();
    }

    public Optional<String> consumeSessionWarning() {
        var warning = sessionWarning;
        sessionWarning = null;
        return Optional.ofNullable(warning);
    }

    public long estimatedContextTokens() {
        return CompactionManager.estimateTokens(agentLoop.messages());
    }

    /** Cumulative input tokens billed this session. */
    public long totalInputTokens() {
        return agentLoop.llm().totalInputTokens();
    }

    /** Cumulative output tokens billed this session. */
    public long totalOutputTokens() {
        return agentLoop.llm().totalOutputTokens();
    }

    /** Estimated cumulative spend this session in USD. */
    public double totalCost() {
        return agentLoop.llm().totalCost();
    }

    /** The session cost budget in USD, or {@code null} if none is set. */
    public Double costBudget() {
        return agentLoop.llm().costBudget();
    }

    public void setSessionName(String name) throws IOException {
        if (sessionFile == null)
            throw new IOException("No active session");
        sessionManager.appendSessionInfo(sessionFile, name);
    }

    public String sessionName() throws IOException {
        return sessionFile != null ? sessionManager.sessionName(sessionFile) : null;
    }

    public SessionManager.CacheMetadata cacheMetadata() throws IOException {
        return sessionManager.latestCacheMetadata(sessionFile);
    }

    public void refreshCacheMetadata() {
        writeCacheMetadata(agentLoop.llm().provider(), agentLoop.llm().model(), sessionSystemPrompt);
    }

    /**
     * Records the effective model and thinking level into a fresh session so it fully describes itself. Resume (e.g.
     * after {@code /transcend}) then restores the exact model the conversation was using, instead of re-deriving it
     * from CLI args + settings.json — which can have drifted since launch.
     */
    public void recordInitialModelState() {
        Path file = this.sessionFile;
        if (disableSession || file == null)
            return;
        var llm = agentLoop.llm();
        try {
            sessionManager.appendModelChange(file, llm.provider(), llm.model(), initialContextWindow);
            if (llm.thinkingLevel() != null)
                sessionManager.appendThinkingLevelChange(file, llm.thinkingLevel().name());
        } catch (IOException e) {
            rememberSessionWarning("initial model state save", e);
        }
    }

    public void checkCacheTarget() throws IOException {
        var metadata = cacheMetadata();
        if (metadata == null) {
            checkCacheMetadata(null);
            return;
        }
        String currentProvider = agentLoop.llm().provider();
        String currentModel = agentLoop.llm().model();
        if (metadata.provider() != null && !metadata.provider().isBlank()
                                        && !metadata.provider().equals(currentProvider)) {
            rememberSessionWarning("[cache] provider changed since session start; LLM server cache may miss");
            return;
        }
        if (metadata.model() != null && !metadata.model().isBlank() && !metadata.model().equals(currentModel)) {
            rememberSessionWarning("[cache] model changed since session start; LLM server cache may miss");
        }
    }

    public void deleteSession(String spec) throws IOException {
        Path target = sessionManager.resolveSession(cwd, spec);
        if (sessionFile != null && target.toAbsolutePath().normalize().equals(sessionFile.toAbsolutePath().normalize()))
            throw new IOException("cannot delete active session");
        Files.deleteIfExists(target);
    }

    private void rememberSessionWarning(String action, IOException e) {
        sessionWarning = action + " failed: " + e.getMessage();
    }

    private void rememberSessionWarning(String message) {
        sessionWarning = message;
    }

    private void switchSession(Path newFile, String reason) throws IOException {
        this.sessionFile = newFile;
        this.disableSession = false;
        SelfRespawn.setSessionFile(newFile);
        var msgs = sessionManager.loadMessages(newFile);
        var metadata = sessionManager.latestCacheMetadata(newFile);
        String restoredPrompt = metadata != null && metadata.systemPrompt() != null
                                        && !metadata.systemPrompt().isBlank()
                                                                        ? metadata.systemPrompt()
                                                                        : baseSystemPrompt;
        sessionSystemPrompt = restoredPrompt;
        agentLoop.clearMessages();
        agentLoop.restoreMessages(msgs);
        agentLoop.replaceSystemPrompt(restoredPrompt);
        extensionRegistry.setSystemPrompt(restoredPrompt);
        agentLoop.notifyHistoryChanged(reason); // let frontends mirroring the transcript re-render
    }

    private void writeCacheMetadata(String provider, String model, String systemPrompt) {
        Path file = this.sessionFile;
        if (disableSession || file == null)
            return;
        try {
            var manifest = ToolManifest.snapshot(toolRegistry);
            sessionManager.appendCacheMetadata(file, systemPrompt, manifest.hash(), manifest.json(), provider, model);
        } catch (Exception e) {
            rememberSessionWarning("cache metadata save", new IOException(e));
        }
    }

    private void checkCacheMetadata(SessionManager.CacheMetadata metadata) {
        if (metadata == null) {
            rememberSessionWarning("[cache] session has no cache metadata; respawn cache hit is not guaranteed");
            return;
        }
        var current = ToolManifest.snapshot(toolRegistry);
        if (metadata.toolManifestHash() == null || metadata.toolManifestHash().isBlank()) {
            rememberSessionWarning("[cache] session has no tool manifest hash; respawn cache hit is not guaranteed");
        } else if (!metadata.toolManifestHash().equals(current.hash())) {
            rememberSessionWarning("[cache] tool manifest changed; LLM server cache may miss");
        }
    }

    public int load(Path file) throws IOException {
        if (this.sessionFile != null && !this.sessionFile.equals(file))
            sessionManager.deleteIfEmpty(this.sessionFile); // drop the auto-created session if we never used it
        switchSession(file, "load");
        return agentLoop.messages().size();
    }

    public int load(String spec) throws IOException {
        return load(sessionManager.resolveSession(cwd, spec));
    }

    public int continueLastSession() throws IOException {
        return load(sessionManager.latestSession(cwd, sessionFile));
    }

    public Path newSession() throws IOException {
        Path previous = this.sessionFile;
        Path newFile = sessionManager.createSession(cwd);
        switchSession(newFile, "new");
        refreshCacheMetadata();
        if (previous != null && !previous.equals(newFile))
            sessionManager.deleteIfEmpty(previous); // discard the prior session if it was never used
        return newFile;
    }

    public Path forkFrom(String entryId) throws IOException {
        if (sessionFile == null)
            throw new IOException("No active session");
        Path newFile = sessionManager.forkSession(sessionFile, entryId, cwd);
        switchSession(newFile, "fork");
        return newFile;
    }

    public Path cloneCurrentBranch() throws IOException {
        if (sessionFile == null)
            throw new IOException("No active session");
        Path newFile = sessionManager.migrate(sessionFile, sessionManager.leafId(), cwd);
        switchSession(newFile, "clone");
        return newFile;
    }

    @Override
    public void close() {
        agentLoop.close();
        if (!disableSession && sessionFile != null)
            sessionManager.deleteIfEmpty(sessionFile);
    }

    private static SubAgent findSummarizeAgent(String cwd) {
        try {
            for (var agent : SubAgent.loadAll(java.nio.file.Path.of(cwd))) {
                if ("summarize".equals(agent.name()))
                    return agent;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
