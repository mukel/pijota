package org.pijota.extensions.testkit;

import org.pijota.extensions.DeliverAs;
import org.pijota.extensions.ExceptionHandler;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.ExtensionKeybinding;
import org.pijota.extensions.ExtensionResult;
import org.pijota.extensions.ExtensionRuntime;
import org.pijota.extensions.UiContext;
import org.pijota.extensions.skills.SkillLoader;
import org.pijota.messages.Message;
import org.pijota.modes.SlashCommand;
import org.pijota.tools.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class FakeExtensionAPI implements ExtensionAPI {

    private final ExtensionRuntime runtime;
    private final List<Tool.ToolDefinition> tools = new ArrayList<>();
    private final List<SlashCommand> slashCommands = new ArrayList<>();
    private final List<ExtensionKeybinding> keybindings = new ArrayList<>();
    private final List<SkillLoader.Skill> skills = new ArrayList<>();
    private final List<Supplier<List<String>>> statusLines = new ArrayList<>();
    private final List<Supplier<List<String>>> footerLines = new ArrayList<>();
    private final List<Supplier<String>> systemPrompts = new ArrayList<>();
    private final List<Supplier<String>> thinkingHeaders = new ArrayList<>();
    private final List<Function<Message, ExtensionResult.MessageRender>> renderers = new ArrayList<>();
    private final Map<Class<? extends ExtensionEvent>, List<Function<ExtensionEvent, Object>>> listeners = new ConcurrentHashMap<>();
    private final List<Message.Custom> sentMessages = new ArrayList<>();
    private final List<String> sentUserMessages = new ArrayList<>();

    public FakeExtensionAPI() {
        this(new FakeExtensionRuntime());
    }

    public FakeExtensionAPI(ExtensionRuntime runtime) {
        this.runtime = runtime;
    }

    public void setUi(UiContext ui) {
        // UI is reached by extensions via runtime().ui(), so route it onto the runtime.
        if (runtime instanceof FakeExtensionRuntime r)
            r.setUi(ui);
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

    public List<Function<Message, ExtensionResult.MessageRender>> renderers() {
        return List.copyOf(renderers);
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
        fire(new ExtensionEvent.TurnStart(turnNumber));
    }

    public void fireTurnEnd(int turnNumber) {
        fire(new ExtensionEvent.TurnEnd(turnNumber));
    }

    public void fireError(String message, boolean retryable) {
        fire(new ExtensionEvent.Error(message, retryable));
    }

    /** Fires a tool call carrying a {@code {"command": ...}} body, the shape the bash/tmux guards inspect. */
    public Object fireToolCall(String tool, String command) {
        return fire(new ExtensionEvent.ToolCall("id", tool, "{\"command\":\"" + command + "\"}", true));
    }

    public SlashCommand slashCommand(String name) {
        return slashCommands.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }

    public Tool.ToolDefinition tool(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    public <T extends ExtensionEvent> List<Function<T, Object>> listeners(Class<T> eventType) {
        return (List<Function<T, Object>>) (List<?>) listeners.getOrDefault(eventType, List.of());
    }

    /** Dispatches an event to its handlers; returns the first non-null result (for decision events) or null. */
    public Object fire(ExtensionEvent event) {
        for (var handler : listeners.getOrDefault(event.getClass(), List.of())) {
            Object result = handler.apply(event);
            if (result != null)
                return result;
        }
        return null;
    }

    public ExtensionResult.InputResult filterInput(String text) {
        String current = text;
        for (var handler : listeners.getOrDefault(ExtensionEvent.Input.class, List.of())) {
            Object result = handler.apply(new ExtensionEvent.Input(current));
            if (result instanceof ExtensionResult.InputResult r) {
                current = r.text();
                if (r.consumed())
                    break;
            }
        }
        return new ExtensionResult.InputResult(current, false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends ExtensionEvent> ExtensionAPI on(Class<E> eventType, Consumer<E> handler) {
        listeners.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(e -> {
            handler.accept((E) e);
            return null;
        });
        return this;
    }

    @Override
    public ExtensionAPI registerMessageRenderer(Function<Message, ExtensionResult.MessageRender> renderer) {
        if (renderer != null)
            renderers.add(renderer);
        return this;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ExtensionAPI onUserBash(Function<ExtensionEvent.UserBash, ExtensionResult.UserBashResult> handler) {
        listeners.computeIfAbsent(ExtensionEvent.UserBash.class, ignored -> new CopyOnWriteArrayList<>())
                                        .add((Function) handler);
        return this;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ExtensionAPI onBashToolCall(
                                    Function<ExtensionEvent.BashToolCall, ExtensionResult.BashToolCallResult> handler) {
        listeners.computeIfAbsent(ExtensionEvent.BashToolCall.class, ignored -> new CopyOnWriteArrayList<>())
                                        .add((Function) handler);
        return this;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ExtensionAPI onToolCall(Function<ExtensionEvent.ToolCall, ExtensionResult.Block> handler) {
        listeners.computeIfAbsent(ExtensionEvent.ToolCall.class, ignored -> new CopyOnWriteArrayList<>())
                                        .add((Function) handler);
        return this;
    }

    @Override
    public ExtensionAPI onInput(Function<String, ExtensionResult.InputResult> handler) {
        listeners.computeIfAbsent(ExtensionEvent.Input.class, ignored -> new CopyOnWriteArrayList<>()).add(e -> {
            if (e instanceof ExtensionEvent.Input input)
                return handler.apply(input.text());
            return null;
        });
        return this;
    }

    @Override
    public void registerTool(Tool tool) {
        // Test double stores a ToolDefinition view so tool(name) keeps working; production registers the Tool directly.
        tools.add(Tool.ToolDefinition.from(tool));
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
        // Mirrors the production ExtensionRegistry: with a UI attached the single-arg form rides the frontend's
        // user-typed path (submitUserMessage), so tests can tell that channel apart from the raw agent channel
        // (the two-arg form), which the job monitor deliberately uses to avoid the visible follow-up queue.
        UiContext ui = runtime.ui();
        if (ui != null)
            ui.submitUserMessage(content);
        else
            sentUserMessages.add(content);
    }

    @Override
    public void sendUserMessage(String content, DeliverAs deliverAs) {
        sentUserMessages.add(content + "|" + deliverAs.name());
    }
}
