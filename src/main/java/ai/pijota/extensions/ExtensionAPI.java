package ai.pijota.extensions;

import ai.pijota.extensions.skills.SkillLoader;
import ai.pijota.modes.SlashCommand;
import ai.pijota.messages.Message;
import ai.pijota.tools.Tool;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public interface ExtensionAPI {

    // ---- event subscription ----

    /**
     * Subscribes to events of the given type. The handler receives the typed event and an {@link ExtensionContext}. Use
     * this for all observe/react ("listener") events — lifecycle, turns, messages, tool execution, provider/model,
     * compaction. The handler may mutate the event where the event exposes mutators (e.g.
     * {@link ExtensionEvent.SessionBeforeCompact}); it returns nothing. Returns {@code this} for chaining.
     *
     * <p>
     * Events that <em>decide</em> the outcome of an action (and so return a typed result) have dedicated methods:
     * {@link #onToolCall}, {@link #onUserBash}, {@link #onBashToolCall}, {@link #onInput}.
     */
    <E extends ExtensionEvent> ExtensionAPI on(Class<E> eventType, BiConsumer<E, ExtensionContext> handler);

    /** Registers a renderer for custom extension messages; the first renderer to produce output wins. */
    ExtensionAPI registerMessageRenderer(BiFunction<Message, ExtensionContext, ExtensionResult.MessageRender> renderer);

    // ---- decision events (return a typed result) ----

    /** Decides whether a tool call may proceed; return a {@link ExtensionResult.Block} to veto it. */
    ExtensionAPI onToolCall(BiFunction<ExtensionEvent.ToolCall, ExtensionContext, ExtensionResult.Block> handler);

    /** Decides whether a user-initiated bash command may run. */
    ExtensionAPI onUserBash(BiFunction<ExtensionEvent.UserBash, ExtensionContext, ExtensionResult.UserBashResult> handler);

    /** Decides whether an LLM-initiated bash tool call may run. */
    ExtensionAPI onBashToolCall(BiFunction<ExtensionEvent.BashToolCall, ExtensionContext, ExtensionResult.BashToolCallResult> handler);

    /** Transforms or consumes raw user input before it is handled. */
    ExtensionAPI onInput(BiFunction<String, ExtensionContext, ExtensionResult.InputResult> handler);

    // ---- registration ----

    /** Registers a full {@link Tool} (keeps its glyph/result-rendering/execution-mode/abort). */
    void registerTool(Tool tool);

    /** Registers a lightweight tool from a definition — convenient for lambda-style example tools. */
    void registerTool(Tool.ToolDefinition tool);

    void registerSlashCommand(SlashCommand command);

    void registerKeybinding(ExtensionKeybinding keybinding);

    void registerSkills(List<SkillLoader.Skill> skills);

    void registerStatusLines(java.util.function.Supplier<List<String>> lines);

    void registerFooterLines(java.util.function.Supplier<List<String>> lines);

    void registerExceptionHandler(ExceptionHandler handler);

    void registerSystemPrompt(java.util.function.Supplier<String> prompt);

    void registerThinkingHeaderText(java.util.function.Supplier<String> text);

    // ---- message injection ----

    /** Send a custom extension message to the session (displayed, not sent to LLM). */
    void sendMessage(Message.Custom custom);

    /** Send a user message to the agent. Triggers a turn when idle. */
    void sendUserMessage(String content);

    /** Send a user message with an explicit {@link DeliverAs} delivery mode. */
    void sendUserMessage(String content, DeliverAs deliverAs);

    ExtensionRuntime runtime();
}
