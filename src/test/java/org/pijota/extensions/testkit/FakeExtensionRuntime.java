package org.pijota.extensions.testkit;

import org.pijota.extensions.AgentContext;
import org.pijota.extensions.ExtensionRuntime;
import org.pijota.extensions.UiContext;
import org.pijota.llm.LlmClient;
import org.pijota.messages.Message;
import org.pijota.testkit.FauxLlm;
import org.pijota.tools.Tool;
import org.pijota.tools.ToolRegistry;
import org.pijota.util.ProcessRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Function;

public class FakeExtensionRuntime implements ExtensionRuntime {

    private Path cwd = Path.of(System.getProperty("user.dir"));
    private String systemPrompt;
    private String sessionName;
    private ToolRegistry toolRegistry;
    private AgentContext agentContext;
    private UiContext ui;
    private LlmClient llm;
    private final FauxLlm builtinLlm = new FauxLlm();
    private final List<Message> messages = new ArrayList<>();
    private final Map<String, String> flags = new ConcurrentHashMap<>();
    private final Map<String, String> keyedStatuses = new ConcurrentSkipListMap<>();
    private final List<String> statusCalls = new ArrayList<>();
    private volatile String lastStatus;
    private boolean busy;
    private boolean closed;
    private boolean aborted;
    private boolean compacted;
    private Function<List<String>, ExecResult> execStub;

    /** Stubs {@link #exec} so tests can simulate a missing/erroring binary without shelling out. */
    public void setExecStub(Function<List<String>, ExecResult> stub) {
        this.execStub = stub;
    }

    public void setCwd(Path cwd) {
        this.cwd = cwd;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public void setToolRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public void setAgentContext(AgentContext agentContext) {
        this.agentContext = agentContext;
    }

    public void setLlm(LlmClient llm) {
        this.llm = llm;
    }

    /** Sets the token counts reported by the built-in LLM used when no explicit LLM or agent context is set. */
    public FakeExtensionRuntime tokens(long input, long output) {
        builtinLlm.tokens(input, output);
        return this;
    }

    /** Seeds a message into the transcript as if it were already present before the extension loaded. */
    public void seedMessage(Message message) {
        messages.add(message);
    }

    public String lastStatus() {
        return lastStatus;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public List<String> statusCalls() {
        return List.copyOf(statusCalls);
    }

    public Map<String, String> keyedStatuses() {
        return Map.copyOf(keyedStatuses);
    }

    public boolean aborted() {
        return aborted;
    }

    public boolean compacted() {
        return compacted;
    }

    @Override
    public LlmClient llm() {
        if (llm != null)
            return llm;
        if (agentContext != null)
            return agentContext.llm();
        return builtinLlm;
    }

    @Override
    public Path cwd() {
        return cwd;
    }

    @Override
    public String systemPrompt() {
        return systemPrompt;
    }

    @Override
    public List<Message> messages() {
        return agentContext != null ? agentContext.messages() : List.copyOf(messages);
    }

    void addMessage(Message msg) {
        messages.add(msg);
    }

    @Override
    public ToolRegistry tools() {
        return toolRegistry;
    }

    @Override
    public List<Tool.ToolInfo> activeTools() {
        return toolRegistry != null ? toolRegistry.activeToolInfo() : List.of();
    }

    @Override
    public void setActiveTools(List<String> toolNames) {
        if (toolRegistry != null)
            toolRegistry.setActiveTools(Set.copyOf(toolNames));
    }

    @Override
    public List<Tool.ToolInfo> availableTools() {
        return toolRegistry != null ? toolRegistry.availableToolInfo() : List.of();
    }

    @Override
    public String sessionName() {
        return sessionName;
    }

    @Override
    public void setSessionName(String name) {
        this.sessionName = name;
    }

    @Override
    public boolean isIdle() {
        return !busy;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void abort() {
        aborted = true;
    }

    @Override
    public void compact(String instructions) {
        compacted = true;
    }

    @Override
    public void setStatus(String message) {
        statusCalls.add(message);
        lastStatus = message;
    }

    @Override
    public void setStatus(String key, String message) {
        if (key == null || key.isBlank())
            return;
        if (message == null || message.isBlank())
            keyedStatuses.remove(key);
        else
            keyedStatuses.put(key, message);
    }

    @Override
    public void clearStatus(String key) {
        if (key != null)
            keyedStatuses.remove(key);
    }

    @Override
    public boolean getFlag(String name, boolean defaultValue) {
        if (ExtensionRuntime.truthy(flags.get("no-" + name)))
            return false;
        String val = flags.get(name);
        if (val == null)
            return defaultValue;
        return ExtensionRuntime.truthy(val);
    }

    @Override
    public Optional<String> getFlag(String name) {
        if (ExtensionRuntime.truthy(flags.get("no-" + name)))
            return Optional.of("false");
        return Optional.ofNullable(flags.get(name));
    }

    public void setFlag(String name, String value) {
        flags.put(name, value);
    }

    @Override
    public ExecResult exec(List<String> command) {
        if (execStub != null)
            return execStub.apply(command);
        try {
            var result = ProcessRunner.command(command).run();
            return new ExecResult(result.exitCode(), result.stdout(), result.stderr());
        } catch (Exception e) {
            return new ExecResult(-1, "", e.getMessage());
        }
    }

    public void setUi(UiContext ui) {
        this.ui = ui;
    }

    @Override
    public UiContext ui() {
        return ui;
    }

    public CompletableFuture<Boolean> confirm(String question) {
        return CompletableFuture.completedFuture(true);
    }
}
