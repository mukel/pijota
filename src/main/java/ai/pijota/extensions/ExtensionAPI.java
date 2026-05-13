package ai.pijota.extensions;

import ai.pijota.extensions.skills.SkillLoader;
import ai.pijota.modes.SlashCommand;
import ai.pijota.tools.Tool;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public interface ExtensionAPI {

    // ---- lifecycle events ----

    ExtensionAPI onSessionStart(BiConsumer<ExtensionEvent.SessionStart, ExtensionContext> handler);
    ExtensionAPI onSessionReady(BiConsumer<ExtensionEvent.SessionReady, ExtensionContext> handler);
    ExtensionAPI onSessionShutdown(BiConsumer<ExtensionEvent.SessionShutdown, ExtensionContext> handler);

    ExtensionAPI onBeforeAgentStart(BiConsumer<ExtensionEvent.BeforeAgentStart, ExtensionContext> handler);
    ExtensionAPI onAgentStart(BiConsumer<ExtensionEvent.AgentStart, ExtensionContext> handler);
    ExtensionAPI onAgentEnd(BiConsumer<ExtensionEvent.AgentEnd, ExtensionContext> handler);

    ExtensionAPI onTurnStart(BiConsumer<ExtensionEvent.TurnStart, ExtensionContext> handler);
    ExtensionAPI onTurnEnd(BiConsumer<ExtensionEvent.TurnEnd, ExtensionContext> handler);

    ExtensionAPI onMessageStart(BiConsumer<ExtensionEvent.MessageStart, ExtensionContext> handler);
    ExtensionAPI onMessageUpdate(BiConsumer<ExtensionEvent.MessageUpdate, ExtensionContext> handler);
    ExtensionAPI onMessageEnd(BiConsumer<ExtensionEvent.MessageEnd, ExtensionContext> handler);

    // ---- session compact ----

    ExtensionAPI onSessionBeforeCompact(BiConsumer<ExtensionEvent.SessionBeforeCompact, ExtensionContext> handler);
    ExtensionAPI onSessionCompact(BiConsumer<ExtensionEvent.SessionCompact, ExtensionContext> handler);

    // ---- tool events ----

    ExtensionAPI onToolCall(BiFunction<ExtensionEvent.ToolCall, ExtensionContext, ExtensionResult.ToolCall> handler);
    ExtensionAPI onToolResult(BiConsumer<ExtensionEvent.ToolResult, ExtensionContext> handler);
    ExtensionAPI onToolExecutionStart(BiConsumer<ExtensionEvent.ToolExecutionStart, ExtensionContext> handler);
    ExtensionAPI onToolExecutionEnd(BiConsumer<ExtensionEvent.ToolExecutionEnd, ExtensionContext> handler);

    // ---- provider / model ----

    ExtensionAPI onBeforeProviderRequest(BiConsumer<ExtensionEvent.BeforeProviderRequest, ExtensionContext> handler);
    ExtensionAPI onModelSelect(BiConsumer<ExtensionEvent.ModelSelect, ExtensionContext> handler);

    // ---- user bash ----

    ExtensionAPI onUserBash(BiConsumer<ExtensionEvent.UserBash, ExtensionContext> handler);

    // ---- input ----

    ExtensionAPI onInput(BiFunction<String, ExtensionContext, ExtensionResult.Input> handler);

    // ---- registration ----

    void registerTool(Tool.ToolDefinition tool);
    void registerSlashCommand(SlashCommand command);
    void registerKeybinding(ExtensionKeybinding keybinding);
    void registerSkills(List<SkillLoader.Skill> skills);

    void registerStatusLines(java.util.function.Supplier<List<String>> lines);
    void registerFooterLines(java.util.function.Supplier<List<String>> lines);
    void registerSystemPrompt(java.util.function.Supplier<String> prompt);

    ExtensionRuntime runtime();
}
