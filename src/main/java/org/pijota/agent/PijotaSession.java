package org.pijota.agent;

import org.pijota.config.AppConfig;
import org.pijota.extensions.ExtensionRegistry;
import org.pijota.extensions.task.SubAgent;
import org.pijota.llm.LlmClient;
import org.pijota.llm.ModelRegistry;
import org.pijota.llm.SummaryResult;
import org.pijota.llm.ThinkingLevel;
import org.pijota.messages.Message;
import org.pijota.tools.Tool;
import org.pijota.tools.ToolManifest;
import org.pijota.tools.ToolRegistry;
import org.pijota.util.ProcessRunner;
import org.pijota.util.SelfRespawn;
import org.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Durable conversational session, the main public API. Wraps the agent loop, session persistence, and extension
 * registry; messages, model/thinking-level changes, and compaction summaries are saved to disk and resume across
 * restarts.
 */
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
                                    boolean noSession, int contextWindow, AppConfig.CompactionConfig compactionConfig,
                                    int maxTurns) throws IOException {
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
        CompactionManager.SummaryGenerator summaryGen = new CompactionManager.SummaryGenerator() {
            @Override
            public String generate(List<Message> msgs, String instr, String prev, BiConsumer<String, String> onPart) {
                var result = generateWithUsage(msgs, instr, prev, onPart);
                return result != null ? result.text() : null;
            }

            @Override
            public SummaryResult generateWithUsage(List<Message> msgs, String instr, String prev,
                                            BiConsumer<String, String> onPart) {
                var summaryAgent = findSummarizeAgent(cwd);
                return llm.summarizeWithUsage(msgs, instr, prev, onPart,
                                                summaryAgent != null ? summaryAgent.model() : null,
                                                summaryAgent != null ? summaryAgent.systemPrompt() : null);
            }
        };
        this.agentLoop = AgentLoop.builder(llm, toolRegistry, cwd).summaryGenerator(summaryGen)
                                        .extensionRegistry(extensionRegistry).contextWindow(contextWindow)
                                        .compactionConfig(compactionConfig).maxTurns(maxTurns).build();
        extensionRegistry.setAgentLoop(this.agentLoop); // the session owns the registry↔loop wiring

        this.agentLoop.addCompactionAppender((summary, count, firstKeptId, usage) -> save("compaction save",
                                        f -> sessionManager.appendCompaction(f, summary, count, firstKeptId, usage)));
        this.agentLoop.addSystemPrompt(systemPrompt);

        this.agentLoop.addEventListener(event -> {
            switch (event) {
                case AgentEvent.AssistantMessage(Message.Assistant msg, var usage) -> {
                    if (usage != null)
                        save("assistant message save", f -> sessionManager.appendMessage(f, msg.withUsage(usage)));
                }
                case AgentEvent.ToolResult(Message.ToolResult msg) ->
                    save("tool result save", f -> sessionManager.appendMessage(f, msg));
                case AgentEvent.CustomMessage(Message.Custom msg) ->
                    save("custom message save", f -> sessionManager.appendMessage(f, msg));
                default -> {}
            }
        });
        this.agentLoop.addEventListener(extensionRegistry::onAgentEvent);
    }

    public CompletableFuture<List<Message>> prompt(String userInput) {
        return prompt(userInput, List.of());
    }

    /**
     * Prompt with attachments. Text-like files (txt/code/docx/…) are extracted and inlined into the prompt here; binary
     * media (image/pdf/audio/video) is carried as attachments and gated to the model's modalities at send time.
     */
    public CompletableFuture<List<Message>> prompt(String userInput, List<Message.Attachment> attachments) {
        var supported = ModelRegistry.inputModalities(llm().provider(), llm().model()); // null = unknown → allow all
        var normalized = AttachmentNormalizer.normalize(userInput, attachments, supported, cwd, llm().provider(),
                                        llm().model());
        String filtered = extensionRegistry.filterInput(normalized.text());
        var userMessage = new Message.User(filtered, normalized.media());
        save("user message save", f -> sessionManager.appendMessage(f, userMessage));
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

    /**
     * The underlying loop; low-level access for the one-shot print runner. Frontends should drive the session via
     * {@link #prompt}/{@link #followUp}/{@link #abort}, not reach in here.
     */
    public AgentLoop agentLoop() {
        return agentLoop;
    }

    /** Look up a registered tool by name; frontends use this for display (glyph, labels). */
    public Optional<Tool> tool(String name) {
        return toolRegistry.get(name);
    }

    public ExtensionRegistry extensionRegistry() {
        return extensionRegistry;
    }

    public Path sessionFile() {
        return sessionFile;
    }

    public String cwd() {
        return cwd;
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

    /** Compacts the context; returns true if anything was actually summarized. */
    public boolean compact(String instructions) {
        return agentLoop.compactWithResult(instructions).compacted();
    }

    public String summarizeCurrentBranch(String instructions) throws IOException {
        var result = agentLoop.llm().summarizeWithUsage(agentLoop.messages(), instructions, null,
                                        (ignoredKind, ignoredDelta) -> {}, null, null);
        String summary = result != null ? result.text() : null;
        if (summary == null || summary.isBlank())
            summary = fallbackBranchSummary(agentLoop.messages(), instructions);
        var msg = new Message.BranchSummary(summary.strip());
        save("branch summary save", f -> sessionManager.appendMessage(f, msg));
        if (sessionFile != null) {
            agentLoop.clearMessages();
            agentLoop.restoreMessages(sessionManager.loadMessages(sessionFile));
            agentLoop.replaceSystemPrompt(sessionSystemPrompt);
        }
        agentLoop.notifyHistoryChanged("branch-summary");
        return msg.summary();
    }

    private static String fallbackBranchSummary(List<Message> messages, String instructions) {
        var sb = new StringBuilder("[degraded · LLM summarizer unavailable]\n");
        if (instructions != null && !instructions.isBlank())
            sb.append("Focus: ").append(instructions.strip()).append("\n");
        int start = Math.max(0, messages.size() - 12);
        for (int i = start; i < messages.size(); i++) {
            Message msg = messages.get(i);
            switch (msg) {
                case Message.User u -> sb.append("User: ").append(shorten(u.content())).append('\n');
                case Message.Assistant a -> sb.append("Assistant: ").append(shorten(a.content())).append('\n');
                case Message.ToolResult tr ->
                    sb.append("Tool ").append(tr.toolName()).append(": ").append(shorten(tr.content())).append('\n');
                default -> {}
            }
        }
        return sb.toString().strip();
    }

    private static String shorten(String text) {
        return Util.ellipsize(text == null ? "" : text.replaceAll("\\s+", " ").strip(), 240);
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
        if (persist)
            save("thinking level save", f -> sessionManager.appendThinkingLevelChange(f, effective.name()));
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
        if (persist)
            save("model change save", f -> {
                sessionManager.appendModelChange(f, provider, model, contextWindow);
                writeCacheMetadata(provider, model, sessionSystemPrompt);
            });
    }

    public ModelSelection latestModelSelection() throws IOException {
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

    public void removeEventListener(Consumer<AgentEvent> listener) {
        agentLoop.removeEventListener(listener);
    }

    /** The live model client (current provider/model + usage), e.g. for an in-process web/Telegram remote control. */
    public LlmClient llm() {
        return agentLoop.llm();
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

    public int branchTo(String entryId) throws IOException {
        if (sessionFile == null)
            throw new IOException("No active session");
        var msgs = sessionManager.branchTo(sessionFile, entryId);
        agentLoop.clearMessages();
        agentLoop.restoreMessages(msgs);
        agentLoop.replaceSystemPrompt(sessionSystemPrompt);
        agentLoop.notifyHistoryChanged("tree");
        return msgs.size();
    }

    public int branchToParentOf(String parentId) throws IOException {
        if (sessionFile == null)
            throw new IOException("No active session");
        if (parentId == null || parentId.isBlank()) {
            sessionManager.branchToRoot(sessionFile);
            agentLoop.clearMessages();
            agentLoop.replaceSystemPrompt(sessionSystemPrompt);
            agentLoop.notifyHistoryChanged("tree");
            return 0;
        }
        return branchTo(parentId);
    }

    public Optional<String> consumeSessionWarning() {
        var warning = sessionWarning;
        sessionWarning = null;
        return Optional.ofNullable(warning);
    }

    public long estimatedContextTokens() {
        // Anchor on the provider's real prompt size: the byte estimate of the current messages plus the fixed overhead
        // (system prompt + tool schemas + tokenizer diff) the provider reported last time. So the count stays within
        // tokenizer error of reality and grows immediately as messages, including the streaming response, are added,
        // instead of sitting at the stale real value until the next response. Before any real usage the overhead is 0,
        // so this falls back to the plain byte estimate.
        long base = CompactionManager.estimateTokens(agentLoop.messages());
        return Math.max(1, base + agentLoop.contextOverhead());
    }

    /** Cumulative input tokens billed this session. */
    public long totalInputTokens() {
        return agentLoop.llm().totalInputTokens();
    }

    /** Cumulative output tokens billed this session. */
    public long totalOutputTokens() {
        return agentLoop.llm().totalOutputTokens();
    }

    /** Cumulative input tokens served from the provider cache this session (cache hits). */
    public long totalCacheReadTokens() {
        return agentLoop.llm().totalCacheReadTokens();
    }

    /** Cumulative input tokens written to the provider cache this session (cache misses that populate it). */
    public long totalCacheWriteTokens() {
        return agentLoop.llm().totalCacheWriteTokens();
    }

    /** Cumulative reasoning (thinking) tokens billed within the output count this session. */
    public long totalReasoningTokens() {
        return agentLoop.llm().totalReasoningTokens();
    }

    /** Estimated cumulative spend this session in USD. */
    public double totalCost() {
        return agentLoop.llm().totalCost();
    }

    /** The session cost budget in USD, or {@code null} if none is set. */
    public Double costBudget() {
        return agentLoop.llm().costBudget();
    }

    /** Whether some usage ran on a model with unknown pricing, so {@link #totalCost} understates the real spend. */
    public boolean hasUnpricedUsage() {
        return agentLoop.llm().hasUnpricedUsage();
    }

    public String provider() {
        return agentLoop.llm().provider();
    }

    public String model() {
        return agentLoop.llm().model();
    }

    public void setSessionName(String name) throws IOException {
        if (sessionFile == null)
            throw new IOException("No active session");
        sessionManager.appendSessionInfo(sessionFile, sanitizeName(name));
    }

    /**
     * A session name must be a single plain line: collapse every control char, newlines and tabs (which would wrap the
     * status bar onto extra lines) and ESC (which could otherwise inject ANSI into it), to spaces, squeeze runs, and
     * trim. Blank ⇒ null (name cleared).
     */
    public static String sanitizeName(String name) {
        if (name == null)
            return null;
        String cleaned = name.replaceAll("\\p{Cntrl}+", " ").strip().replaceAll(" {2,}", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    public String sessionName() throws IOException {
        return sessionFile != null ? sessionManager.sessionName(sessionFile) : null;
    }

    // Package-private: an internal persistence detail (carries tool-manifest hashes), not part of the frontend API.
    SessionManager.CacheMetadata cacheMetadata() throws IOException {
        return sessionManager.latestCacheMetadata(sessionFile);
    }

    public void refreshCacheMetadata() {
        writeCacheMetadata(agentLoop.llm().provider(), agentLoop.llm().model(), sessionSystemPrompt);
    }

    /**
     * Records the effective model and thinking level into a fresh session so it fully describes itself. Resume (e.g.
     * after {@code /transcend}) then restores the exact model the conversation was using, instead of re-deriving it
     * from CLI args + settings.json, which can have drifted since launch.
     */
    public void recordInitialModelState() {
        var llm = agentLoop.llm();
        save("initial model state save", f -> {
            sessionManager.appendModelChange(f, llm.provider(), llm.model(), initialContextWindow);
            if (llm.thinkingLevel() != null)
                sessionManager.appendThinkingLevelChange(f, llm.thinkingLevel().name());
        });
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

    public boolean deleteSession(String spec) throws IOException {
        Path target = sessionManager.resolveSession(cwd, spec);
        if (sessionFile != null && target.toAbsolutePath().normalize().equals(sessionFile.toAbsolutePath().normalize()))
            throw new IOException("cannot delete active session");
        try {
            var result = ProcessRunner.command("trash", target.toString()).run();
            if (result.exitCode() == 0)
                return true;
        } catch (IOException e) {
            if (!ProcessRunner.isCommandNotFound(e))
                throw e;
        }
        Files.deleteIfExists(target);
        return false;
    }

    /**
     * The single persistence gate: run the write iff the session is enabled and open, capturing failures as warnings.
     */
    private void save(String what, SessionWrite write) {
        Path file = this.sessionFile;
        if (disableSession || file == null)
            return;
        try {
            write.run(file);
        } catch (IOException e) {
            rememberSessionWarning(what, e);
        }
    }

    @FunctionalInterface
    private interface SessionWrite {
        void run(Path file) throws IOException;
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
            for (var agent : SubAgent.loadAll(Path.of(cwd))) {
                if ("summarize".equals(agent.name()))
                    return agent;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
