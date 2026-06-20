package ai.pijota.extensions;

import ai.pijota.extensions.skills.SkillLoader;
import ai.pijota.modes.SlashCommand;
import ai.pijota.messages.Message;
import ai.pijota.tools.Tool;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * The registration and control surface exposed to {@link Extension#init}. Subscribe to events for observe-only or
 * decision hooks, register tools/slash-commands/keybindings/prompt-contributions, render custom messages, and inject
 * messages into the session. Returns {@code this} for chaining on all hook/registration methods. The {@link #runtime()}
 * handle is valid immediately and stays valid for the entire session lifetime.
 */
public interface ExtensionAPI {

    // ---- event subscription ----

    /**
     * Subscribe to observe-only lifecycle/turn/message/tool events. The handler receives the typed event and an
     * {@link ExtensionContext}. For events that <em>decide</em> an outcome, use the dedicated hooks:
     * {@link #onToolCall}, {@link #onUserBash}, {@link #onBashToolCall}, {@link #onInput}.
     */
    <E extends ExtensionEvent> ExtensionAPI on(Class<E> eventType, BiConsumer<E, ExtensionContext> handler);

    /**
     * Render custom extension messages in the TUI. The first renderer that returns non-empty output wins. Use distinct
     * {@code customType} values to avoid rendering messages meant for other extensions.
     */
    ExtensionAPI registerMessageRenderer(BiFunction<Message, ExtensionContext, ExtensionResult.MessageRender> renderer);

    // ---- decision events (return a typed result) ----

    /** Decides whether a tool call may proceed — return {@link ExtensionResult.Block} to veto. */
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
