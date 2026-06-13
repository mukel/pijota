package ai.pijota.extensions.testkit;

import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.ExtensionContext;
import ai.pijota.extensions.ExtensionEvent;
import ai.pijota.extensions.ExtensionKeybinding;
import ai.pijota.extensions.ExtensionResult;
import ai.pijota.extensions.ExtensionRuntime;
import ai.pijota.extensions.ExceptionHandler;
import ai.pijota.extensions.UiContext;
import ai.pijota.extensions.skills.SkillLoader;
import ai.pijota.messages.Message;
import ai.pijota.modes.SlashCommand;
import ai.pijota.tools.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class FakeExtensionAPI implements ExtensionAPI {

    private final ExtensionRuntime runtime;
    private UiContext ui;
    private final List<Tool.ToolDefinition> tools = new ArrayList<>();
    private final List<SlashCommand> slashCommands = new ArrayList<>();
    private final List<ExtensionKeybinding> keybindings = new ArrayList<>();
    private final List<SkillLoader.Skill> skills = new ArrayList<>();
    private final List<Supplier<List<String>>> statusLines = new ArrayList<>();
    private final List<Supplier<List<String>>> footerLines = new ArrayList<>();
    private final List<Supplier<String>> systemPrompts = new ArrayList<>();
    private final List<Supplier<String>> thinkingHeaders = new ArrayList<>();
    private final List<BiFunction<Message, ExtensionContext, ExtensionResult.MessageRender>> renderers = new ArrayList<>();
    private final Map<String, List<BiFunction<ExtensionEvent, ExtensionContext, Object>>> listeners = new ConcurrentHashMap<>();
    private final List<Message.Custom> sentMessages = new ArrayList<>();
    private final List<String> sentUserMessages = new ArrayList<>();

    public FakeExtensionAPI() {
        this(new FakeExtensionRuntime());
    }

    public FakeExtensionAPI(ExtensionRuntime runtime) {
        this.runtime = runtime;
    }

    public void setUi(UiContext ui) {
        this.ui = ui;
    }

    public ExtensionRuntime runtime() {
        return runtime;
    }

    public List<Tool.ToolDefinition> tools() {
        return List.copyOf(tools);
    }

    public List<SlashCommand> slashCommands() {
        return List.copyOf(slashCommands);
    }

    public List<ExtensionKeybinding> keybindings() {
        return List.copyOf(keybindings);
    }

    public List<SkillLoader.Skill> skills() {
        return List.copyOf(skills);
    }

    public List<Supplier<List<String>>> statusLineSuppliers() {
        return List.copyOf(statusLines);
    }

    public List<Supplier<List<String>>> footerLineSuppliers() {
        return List.copyOf(footerLines);
    }

    public List<Supplier<String>> systemPromptSuppliers() {
        return List.copyOf(systemPrompts);
    }

    public List<Supplier<String>> thinkingHeaderSuppliers() {
        return List.copyOf(thinkingHeaders);
    }

    public List<Message.Custom> sentMessages() {
        return List.copyOf(sentMessages);
    }

    public List<String> sentUserMessages() {
        return List.copyOf(sentUserMessages);
    }

    /** Content of the most recently sent custom message, or empty string if none. */
    public String lastSentMessage() {
        return sentMessages.isEmpty() ? "" : sentMessages.getLast().content();
    }

    /**
     * Current single-line status, falling back to the first registered status-line supplier. Lets tests assert on what
     * the user would see without caring whether the extension pushed a status or registered a status line.
     */
    public String status() {
        String pushed = runtime instanceof FakeExtensionRuntime r ? r.lastStatus() : null;
        if (pushed == null && !statusLines.isEmpty())
            return String.join("\n", statusLines.getFirst().get());
        return pushed == null ? "" : pushed;
    }

    /** Sets the token counts reported by the runtime's LLM (see {@link FakeExtensionRuntime#tokens}). */
    public FakeExtensionAPI tokens(long input, long output) {
        if (runtime instanceof FakeExtensionRuntime r)
            r.tokens(input, output);
        return this;
    }

    /** Seeds a message into the transcript as if it predated the extension. */
    public void seedMessage(Message message) {
        if (runtime instanceof FakeExtensionRuntime r)
            r.seedMessage(message);
    }

    public void fireTurnStart(int turnNumber) {
        fire("turn_start", new ExtensionEvent.TurnStart(turnNumber));
    }

    public void fireTurnEnd(int turnNumber) {
        fire("turn_end", new ExtensionEvent.TurnEnd(turnNumber));
    }

    public void fireSessionReady() {
        fire("session_ready", new ExtensionEvent.SessionReady());
    }

    public SlashCommand slashCommand(String name) {
        return slashCommands.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }

    public Tool.ToolDefinition tool(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    public <T extends ExtensionEvent> List<BiFunction<T, ExtensionContext, Object>> listeners(String name) {
        return (List<BiFunction<T, ExtensionContext, Object>>) (List<?>) listeners.getOrDefault(name, List.of());
    }

    public Object fire(String name, ExtensionEvent event) {
        var ctx = new ExtensionContext(ui, runtime);
        for (var handler : listeners.getOrDefault(name, List.of())) {
            Object result = handler.apply(event, ctx);
            if (result != null)
                return result;
        }
        return null;
    }

    public ExtensionResult.InputResult filterInput(String text) {
        var ctx = new ExtensionContext(ui, runtime);
        String current = text;
        for (var handler : listeners.getOrDefault("input", List.of())) {
            Object result = handler.apply(new ExtensionEvent.Input(current), ctx);
            if (result instanceof ExtensionResult.InputResult r) {
                current = r.text();
                if (r.consumed())
                    break;
            }
        }
        return new ExtensionResult.InputResult(current, false);
    }

    private ExtensionAPI on(String name, BiConsumer<? extends ExtensionEvent, ExtensionContext> handler) {
        listeners.computeIfAbsent(name, _ -> new CopyOnWriteArrayList<>()).add((e, ctx) -> {
            ((BiConsumer<ExtensionEvent, ExtensionContext>) handler).accept(e, ctx);
            return null;
        });
        return this;
    }

    @Override
    public ExtensionAPI onSessionStart(BiConsumer<ExtensionEvent.SessionStart, ExtensionContext> handler) {
        return on("session_start", handler);
    }

    @Override
    public ExtensionAPI onSessionReady(BiConsumer<ExtensionEvent.SessionReady, ExtensionContext> handler) {
        return on("session_ready", handler);
    }

    @Override
    public ExtensionAPI onSessionShutdown(BiConsumer<ExtensionEvent.SessionShutdown, ExtensionContext> handler) {
        return on("session_shutdown", handler);
    }

    @Override
    public ExtensionAPI onBeforeAgentStart(BiConsumer<ExtensionEvent.BeforeAgentStart, ExtensionContext> handler) {
        return on("before_agent_start", handler);
    }

    @Override
    public ExtensionAPI onAgentStart(BiConsumer<ExtensionEvent.AgentStart, ExtensionContext> handler) {
        return on("agent_start", handler);
    }

    @Override
    public ExtensionAPI onAgentEnd(BiConsumer<ExtensionEvent.AgentEnd, ExtensionContext> handler) {
        return on("agent_end", handler);
    }

    @Override
    public ExtensionAPI onTurnStart(BiConsumer<ExtensionEvent.TurnStart, ExtensionContext> handler) {
        return on("turn_start", handler);
    }

    @Override
    public ExtensionAPI onTurnEnd(BiConsumer<ExtensionEvent.TurnEnd, ExtensionContext> handler) {
        return on("turn_end", handler);
    }

    @Override
    public ExtensionAPI onMessageStart(BiConsumer<ExtensionEvent.MessageStart, ExtensionContext> handler) {
        return on("message_start", handler);
    }

    @Override
    public ExtensionAPI onMessageUpdate(BiConsumer<ExtensionEvent.MessageUpdate, ExtensionContext> handler) {
        return on("message_update", handler);
    }

    @Override
    public ExtensionAPI onMessageEnd(BiConsumer<ExtensionEvent.MessageEnd, ExtensionContext> handler) {
        return on("message_end", handler);
    }

    @Override
    public ExtensionAPI registerMessageRenderer(
                                    BiFunction<Message, ExtensionContext, ExtensionResult.MessageRender> renderer) {
        if (renderer != null)
            renderers.add(renderer);
        return this;
    }

    @Override
    public ExtensionAPI onSessionBeforeCompact(
                                    BiConsumer<ExtensionEvent.SessionBeforeCompact, ExtensionContext> handler) {
        return on("session_before_compact", handler);
    }

    @Override
    public ExtensionAPI onSessionCompact(BiConsumer<ExtensionEvent.SessionCompact, ExtensionContext> handler) {
        return on("session_compact", handler);
    }

    @Override
    public ExtensionAPI onToolResult(BiConsumer<ExtensionEvent.ToolResult, ExtensionContext> handler) {
        return on("tool_result", handler);
    }

    @Override
    public ExtensionAPI onToolExecutionStart(BiConsumer<ExtensionEvent.ToolExecutionStart, ExtensionContext> handler) {
        return on("tool_execution_start", handler);
    }

    @Override
    public ExtensionAPI onToolExecutionEnd(BiConsumer<ExtensionEvent.ToolExecutionEnd, ExtensionContext> handler) {
        return on("tool_execution_end", handler);
    }

    @Override
    public ExtensionAPI onBeforeProviderRequest(
                                    BiConsumer<ExtensionEvent.BeforeProviderRequest, ExtensionContext> handler) {
        return on("before_provider_request", handler);
    }

    @Override
    public ExtensionAPI onModelSelect(BiConsumer<ExtensionEvent.ModelSelect, ExtensionContext> handler) {
        return on("model_select", handler);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ExtensionAPI onUserBash(BiFunction<ExtensionEvent.UserBash, ExtensionContext, ExtensionResult.UserBashResult> handler) {
        listeners.computeIfAbsent("user_bash", _ -> new CopyOnWriteArrayList<>()).add((BiFunction) handler);
        return this;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ExtensionAPI onBashToolCall(
                                    BiFunction<ExtensionEvent.BashToolCall, ExtensionContext, ExtensionResult.BashToolCallResult> handler) {
        listeners.computeIfAbsent("bash_tool_call", _ -> new CopyOnWriteArrayList<>()).add((BiFunction) handler);
        return this;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ExtensionAPI onToolCall(BiFunction<ExtensionEvent.ToolCall, ExtensionContext, ExtensionResult.Block> handler) {
        listeners.computeIfAbsent("tool_call", _ -> new CopyOnWriteArrayList<>()).add((BiFunction) handler);
        return this;
    }

    @Override
    public ExtensionAPI onInput(BiFunction<String, ExtensionContext, ExtensionResult.InputResult> handler) {
        listeners.computeIfAbsent("input", _ -> new CopyOnWriteArrayList<>()).add((e, ctx) -> {
            if (e instanceof ExtensionEvent.Input input)
                return handler.apply(input.text(), ctx);
            return null;
        });
        return this;
    }

    @Override
    public void registerTool(Tool.ToolDefinition tool) {
        tools.add(tool);
    }

    @Override
    public void registerSlashCommand(SlashCommand command) {
        slashCommands.add(command);
    }

    @Override
    public void registerKeybinding(ExtensionKeybinding keybinding) {
        if (keybinding != null)
            keybindings.add(keybinding);
    }

    @Override
    public void registerSkills(List<SkillLoader.Skill> skills) {
        if (skills != null)
            this.skills.addAll(skills);
    }

    @Override
    public void registerStatusLines(Supplier<List<String>> lines) {
        if (lines != null)
            statusLines.add(lines);
    }

    @Override
    public void registerFooterLines(Supplier<List<String>> lines) {
        if (lines != null)
            footerLines.add(lines);
    }

    @Override
    public void registerExceptionHandler(ExceptionHandler handler) {}

    @Override
    public void register(String key, Supplier<String> getter) {}

    @Override
    public void register(String key, Supplier<String> getter, Consumer<String> setter) {}

    @Override
    public Optional<String> registerValue(String key) {
        return Optional.empty();
    }

    @Override
    public void registerSystemPrompt(Supplier<String> prompt) {
        if (prompt != null)
            systemPrompts.add(prompt);
    }

    @Override
    public void registerThinkingHeaderText(Supplier<String> text) {
        if (text != null)
            thinkingHeaders.add(text);
    }

    @Override
    public void sendMessage(Message.Custom custom) {
        sentMessages.add(custom);
        if (runtime instanceof FakeExtensionRuntime r)
            r.addMessage(custom);
    }

    @Override
    public void sendUserMessage(String content) {
        sentUserMessages.add(content);
    }

    @Override
    public void sendUserMessage(String content, String deliverAs) {
        sentUserMessages.add(content + "|" + deliverAs);
    }
}
