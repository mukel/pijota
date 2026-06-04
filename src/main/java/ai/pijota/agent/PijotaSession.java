package ai.pijota.agent;

import ai.pijota.config.AppConfig;
import ai.pijota.extensions.ExtensionRegistry;
import ai.pijota.extensions.task.SubAgent;
import ai.pijota.llm.LlmService;
import ai.pijota.llm.ThinkingLevel;
import ai.pijota.messages.Message;
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
    private volatile Path sessionFile;
    private final ExtensionRegistry extensionRegistry;
    private final String baseSystemPrompt;
    private final String cwd;
    private boolean disableSession;
    private volatile String sessionWarning;

    public PijotaSession(
        LlmService llm,
        ToolRegistry toolRegistry,
        SessionManager sessionManager,
        ExtensionRegistry extensionRegistry,
        String systemPrompt,
        String cwd,
        boolean noSession,
        int contextWindow,
        AppConfig.CompactionConfig compactionConfig
    ) throws IOException {
        this.sessionManager = sessionManager;
        this.extensionRegistry = extensionRegistry;
        this.baseSystemPrompt = systemPrompt;
        this.cwd = cwd;
        this.disableSession = noSession;
        this.sessionFile = noSession ? null : sessionManager.createSession(cwd);
        SelfRespawn.setSessionFile(this.sessionFile);
        var summaryGen = (CompactionManager.SummaryGenerator) (msgs, instr, prev, onPart) -> {
            var summaryAgent = findSummarizeAgent(cwd);
            if (summaryAgent != null && summaryAgent.systemPrompt() != null) {
                return llm.summarize(msgs, instr, prev, onPart,
                    summaryAgent.model(), summaryAgent.systemPrompt());
            }
            if (summaryAgent != null && summaryAgent.model() != null) {
                return llm.summarize(msgs, instr, prev, onPart,
                    summaryAgent.model(), null);
            }
            return llm.summarize(msgs, instr, prev, onPart);
        };
        this.agentLoop = AgentLoop.builder(llm, toolRegistry, cwd)
            .summaryGenerator(summaryGen)
            .extensionRegistry(extensionRegistry)
            .contextWindow(contextWindow)
            .compactionConfig(compactionConfig)
            .build();

        this.agentLoop.addCompactionAppender((summary, count, firstKeptId) -> {
            Path file = this.sessionFile;
            if (!disableSession && file != null) {
                try { sessionManager.appendCompaction(file, summary, count, firstKeptId); }
                catch (IOException e) { rememberSessionWarning("compaction save", e); }
            }
        });
        this.agentLoop.addSystemPrompt(systemPrompt);

        this.agentLoop.addEventListener(event -> {
            switch (event) {
                case AgentEvent.TurnStart(int turn) -> extensionRegistry.fireTurnStart(turn);
                case AgentEvent.TurnEnd(int turn) -> extensionRegistry.fireTurnEnd(turn);
                case AgentEvent.MessageStreamStart(String _) -> extensionRegistry.fireMessageStart();
                case AgentEvent.MessageStreamDelta(String delta) -> extensionRegistry.fireMessageUpdate(delta);
                case AgentEvent.MessageStreamEnd(String _) -> extensionRegistry.fireMessageEnd();
                case AgentEvent.AssistantMessage(Message.Assistant msg, var usage) -> {
                    if (usage != null) {
                        Path file = this.sessionFile;
                        if (!disableSession && file != null) {
                            try { sessionManager.appendMessage(file, msg); }
                            catch (IOException e) { rememberSessionWarning("assistant message save", e); }
                        }
                    }
                }
                case AgentEvent.ToolResult(Message.ToolResult msg) -> {
                    Path file = this.sessionFile;
                    if (!disableSession && file != null) {
                        try { sessionManager.appendMessage(file, msg); }
                        catch (IOException e) { rememberSessionWarning("tool result save", e); }
                    }
                }
                default -> {}
            }
        });
    }

    public CompletableFuture<List<Message>> prompt(String userInput) {
        String filtered = extensionRegistry.filterInput(userInput);
        var userMessage = new Message.User(filtered);
        Path file = this.sessionFile;
        if (!disableSession && file != null) {
            try { sessionManager.appendMessage(file, userMessage); }
            catch (IOException e) { rememberSessionWarning("user message save", e); }
        }
        agentLoop.replaceSystemPrompt(extensionRegistry.fireBeforeAgentStart(agentLoop.messages(), baseSystemPrompt));
        extensionRegistry.fireAgentStart();
        return agentLoop.prompt(userMessage)
            .thenApply(messages -> {
                extensionRegistry.fireAfterAgentEnd();
                SelfRespawn.maybeRespawn();
                String err = SelfRespawn.lastRespawnError();
                if (err != null) rememberSessionWarning("transcend", new IOException(err));
                return messages;
            });
    }

    // -- Session management --

    public AgentLoop agentLoop() { return agentLoop; }
    public ExtensionRegistry extensionRegistry() { return extensionRegistry; }
    public Path sessionFile() { return sessionFile; }
    public List<Message> messages() { return agentLoop.messages(); }
    public List<Message.User> userMessages() { return agentLoop.userMessages(); }
    public String lastAssistantContent() { return agentLoop.lastAssistantContent(); }
    public Optional<String> undoLastUserTurn() { return agentLoop.undoLastUserTurn(); }
    public void abort() { agentLoop.abort(); }
    public void followUp(String message) { agentLoop.followUp(message); }
    public boolean isBusy() { return agentLoop.isBusy(); }
    public void stripSystemRemindersFromContext() { agentLoop.stripSystemRemindersFromContext(); }
    public void fireUserBash(String command, boolean excludeFromContext) {
        extensionRegistry.fireUserBash(command, excludeFromContext);
    }
    public CompactionManager.CompactionResult compact(String instructions) { return agentLoop.compactWithResult(instructions); }
    public void setAutoCompactionEnabled(boolean enabled) { agentLoop.setAutoCompactionEnabled(enabled); }
    public void setContextWindow(long contextWindow) { agentLoop.setContextWindow(contextWindow); }
    public void setThinkingLevel(ThinkingLevel level) { agentLoop.llm().setThinkingLevel(level); }

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
            try { sessionManager.appendModelChange(file, provider, model, contextWindow); }
            catch (IOException e) { rememberSessionWarning("model change save", e); }
        }
    }

    public SessionManager.ModelSelection latestModelSelection() throws IOException {
        return sessionManager.latestModelSelection(sessionFile);
    }

    public void addEventListener(Consumer<AgentEvent> listener) {
        agentLoop.addEventListener(listener);
    }

    public List<Path> listSessions() { return sessionManager.listSessions(cwd); }

    public List<Path> resumableSessions() {
        Path current = sessionFile;
        return sessionManager.listSessions(cwd).stream()
            .filter(path -> current == null || !path.equals(current))
            .toList();
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

    public String leafId() { return sessionManager.leafId(); }

    public Optional<String> consumeSessionWarning() {
        var warning = sessionWarning;
        sessionWarning = null;
        return Optional.ofNullable(warning);
    }

    public long estimatedContextTokens() {
        return CompactionManager.estimateTokens(agentLoop.messages());
    }

    public void setSessionName(String name) throws IOException {
        if (sessionFile == null) throw new IOException("No active session");
        sessionManager.appendSessionInfo(sessionFile, name);
    }

    public String sessionName() throws IOException {
        return sessionFile != null ? sessionManager.sessionName(sessionFile) : null;
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

    private void switchSession(Path newFile) throws IOException {
        this.sessionFile = newFile;
        this.disableSession = false;
        SelfRespawn.setSessionFile(newFile);
        var msgs = sessionManager.loadMessages(newFile);
        agentLoop.clearMessages();
        agentLoop.restoreMessages(msgs);
        agentLoop.replaceSystemPrompt(baseSystemPrompt);
    }

    public int load(Path file) throws IOException {
        switchSession(file);
        return agentLoop.messages().size();
    }

    public int load(String spec) throws IOException {
        return load(sessionManager.resolveSession(cwd, spec));
    }

    public int continueLastSession() throws IOException {
        return load(sessionManager.latestSession(cwd, sessionFile));
    }

    public Path newSession() throws IOException {
        Path newFile = sessionManager.createSession(cwd);
        switchSession(newFile);
        return newFile;
    }

    public Path forkFrom(String entryId) throws IOException {
        if (sessionFile == null) throw new IOException("No active session");
        Path newFile = sessionManager.forkSession(sessionFile, entryId, cwd);
        switchSession(newFile);
        return newFile;
    }

    public Path cloneCurrentBranch() throws IOException {
        if (sessionFile == null) throw new IOException("No active session");
        Path newFile = sessionManager.migrate(sessionFile, sessionManager.leafId(), cwd);
        switchSession(newFile);
        return newFile;
    }

    @Override
    public void close() {
        agentLoop.close();
    }

    private static SubAgent findSummarizeAgent(String cwd) {
        try {
            for (var agent : SubAgent.loadAll(java.nio.file.Path.of(cwd))) {
                if ("summarize".equals(agent.name())) return agent;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
