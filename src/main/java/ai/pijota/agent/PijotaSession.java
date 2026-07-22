package ai.pijota.agent;

import ai.pijota.config.AppConfig;
import ai.pijota.extensions.ExtensionRegistry;
import ai.pijota.extensions.task.SubAgent;
import ai.pijota.llm.LlmClient;
import ai.pijota.llm.Modality;
import ai.pijota.llm.ModelRegistry;
import ai.pijota.llm.ThinkingLevel;
import ai.pijota.messages.Message;
import ai.pijota.tools.Tool;
import ai.pijota.tools.ToolManifest;
import ai.pijota.tools.ToolRegistry;
import ai.pijota.util.DocumentText;
import ai.pijota.util.SelfRespawn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Durable conversational session — the main public API. Wraps the agent loop, session persistence, and extension
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
        CompactionManager.SummaryGenerator summaryGen = (msgs, instr, prev, onPart) -> {
            var summaryAgent = findSummarizeAgent(cwd);
            return llm.summarize(msgs, instr, prev, onPart, summaryAgent != null ? summaryAgent.model() : null,
                                            summaryAgent != null ? summaryAgent.systemPrompt() : null);
        };
        this.agentLoop = AgentLoop.builder(llm, toolRegistry, cwd).summaryGenerator(summaryGen)
                                        .extensionRegistry(extensionRegistry).contextWindow(contextWindow)
                                        .compactionConfig(compactionConfig).maxTurns(maxTurns).build();
        extensionRegistry.setAgentLoop(this.agentLoop); // the session owns the registry↔loop wiring

        this.agentLoop.addCompactionAppender((summary, count, firstKeptId) -> save("compaction save",
                                        f -> sessionManager.appendCompaction(f, summary, count, firstKeptId)));
        this.agentLoop.addSystemPrompt(systemPrompt);

        this.agentLoop.addEventListener(event -> {
            switch (event) {
                case AgentEvent.AssistantMessage(Message.Assistant msg, var usage) -> {
                    if (usage != null)
                        save("assistant message save", f -> sessionManager.appendMessage(f, msg));
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
        var normalized = normalizeAttachments(userInput, attachments, supported, cwd);
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

    private record Normalized(String text, List<Message.Attachment> media) {
    }

    /**
     * Splits attachments: text-like files are extracted and appended to the prompt; binary media is kept as-is.
     * {@code supported} is the active model's input modalities ({@code null} = unknown → allow all); a PDF a model
     * can't read natively is staged to a file under {@code cwd} so it can be read with the {@code web_pdf_to_markdown}
     * tool.
     */
    private static Normalized normalizeAttachments(String text, List<Message.Attachment> attachments,
                                    Set<Modality> supported, String cwd) {
        if (attachments == null || attachments.isEmpty())
            return new Normalized(text == null ? "" : text, List.of());
        var media = new ArrayList<Message.Attachment>();
        var sb = new StringBuilder(text == null ? "" : text);
        for (var a : attachments) {
            String name = a.filename() != null && !a.filename().isBlank() ? a.filename() : "file";
            Modality mod = Modality.fromMime(a.mime());
            if (mod != Modality.TEXT) {
                // a PDF the model can't read natively → stage it to disk so it can be read with the web_pdf_to_markdown
                // tool
                if (mod == Modality.PDF && supported != null && !supported.contains(Modality.PDF)) {
                    String rel = stageFile(cwd, name, a.base64());
                    sb.append(rel != null
                                                    ? "\n\n[Attached PDF \"" + name + "\" saved to " + rel
                                                                                    + " — this model can't read PDFs "
                                                                                    + "directly; use the web_pdf_to_markdown tool to read it.]"
                                                    : "\n\n[attachment \"" + name + "\" couldn't be saved]");
                }
                // image formats the providers can't read (HEIC/TIFF/BMP/…) — reject rather than let the API 400 later
                else if (mod == Modality.IMAGE && !Modality.isSupportedImage(a.mime()))
                    sb.append("\n\n[attachment \"").append(name).append("\" omitted — ")
                                                    .append(a.mime().substring(a.mime().indexOf('/') + 1)
                                                                                    .toUpperCase(Locale.ROOT))
                                                    .append(" images aren't supported; use JPEG/PNG/GIF/WebP]");
                else if (a.approxBytes() <= Message.Attachment.MAX_BYTES) // core size floor — every frontend inherits
                                                                          // it
                    media.add(a); // image / pdf / audio / video → sent as a typed content part (gated by the model)
                else // never drop silently — leave a note so the model (and transcript) know it was omitted
                    sb.append("\n\n[attachment \"").append(name).append("\" omitted — larger than 5 MB]");
                continue;
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(a.base64());
            } catch (RuntimeException e) {
                bytes = null;
            }
            String extracted = bytes == null ? null : DocumentText.extract(a.mime(), a.filename(), bytes);
            if (extracted != null && !extracted.isBlank())
                sb.append("\n\n<file name=\"").append(name).append("\">\n").append(extracted).append("\n</file>");
            else
                sb.append("\n\n[attachment \"").append(name).append("\" couldn't be read as text]");
        }
        return new Normalized(sb.toString(), media);
    }

    /**
     * Writes an uploaded file under {@code <cwd>/.pijota/uploads/} so a tool can read it; returns the relative path or
     * null.
     */
    private static String stageFile(String cwd, String name, String base64) {
        try {
            Path dir = Path.of(cwd, ".pijota", "uploads");
            Files.createDirectories(dir);
            Path file = dir.resolve(name.replaceAll("[^A-Za-z0-9._-]", "_"));
            Files.write(file, Base64.getDecoder().decode(base64));
            return Path.of(cwd).relativize(file).toString();
        } catch (Exception e) {
            return null;
        }
    }

    // -- Session management --

    /**
     * The underlying loop — low-level access for the one-shot print runner; frontends should drive the session via
     * {@link #prompt}/{@link #followUp}/{@link #abort}, not reach in here.
     */
    public AgentLoop agentLoop() {
        return agentLoop;
    }

    /** Look up a registered tool by name — frontends use this for display (glyph, labels). */
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

    /** The live model client (current provider/model + usage) — e.g. for an in-process web/Telegram remote control. */
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

    public Optional<String> consumeSessionWarning() {
        var warning = sessionWarning;
        sessionWarning = null;
        return Optional.ofNullable(warning);
    }

    public long estimatedContextTokens() {
        // Anchor on the provider's real prompt size: the byte estimate of the current messages plus the fixed overhead
        // (system prompt + tool schemas + tokenizer diff) the provider reported last time. So the count stays within
        // tokenizer error of reality and grows immediately as messages — including the streaming response — are added,
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
        sessionManager.appendSessionInfo(sessionFile, name);
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
     * from CLI args + settings.json — which can have drifted since launch.
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

    public void deleteSession(String spec) throws IOException {
        Path target = sessionManager.resolveSession(cwd, spec);
        if (sessionFile != null && target.toAbsolutePath().normalize().equals(sessionFile.toAbsolutePath().normalize()))
            throw new IOException("cannot delete active session");
        Files.deleteIfExists(target);
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
