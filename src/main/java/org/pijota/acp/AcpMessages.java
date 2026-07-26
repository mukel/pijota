package org.pijota.acp;

import org.pijota.messages.Message;
import org.pijota.modes.SlashCommand;
import org.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure ACP wire-format mapping: builds the {@code session/update} payloads, the {@code initialize} result, and the
 * {@code session/request_permission} params, and parses prompt content blocks and permission replies. Every method is a
 * total function of its arguments — no state, no IO — so the whole wire mapping is unit-testable in isolation (mirrors
 * opencode's {@code content.ts}/{@code tool.ts}). {@link AcpFrontend} owns all state/IO and wraps these payloads with
 * the live {@code sessionId} before sending.
 */
final class AcpMessages {

    private AcpMessages() {}

    // ---- initialize ----

    /** The {@code initialize} result for the negotiated protocol version. */
    static ObjectNode initializeResult(int protocolVersion) {
        ObjectNode result = Util.MAPPER.createObjectNode();
        result.put("protocolVersion", protocolVersion);
        ObjectNode caps = result.putObject("agentCapabilities");
        caps.put("loadSession", false);
        ObjectNode prompt = caps.putObject("promptCapabilities");
        prompt.put("image", true);
        prompt.put("audio", false);
        prompt.put("embeddedContext", true);
        result.putArray("authMethods"); // no auth: credentials come from pijota's own settings/env
        return result;
    }

    // ---- session/update payloads (wrap with the live sessionId before sending) ----

    /** An {@code agent_message_chunk} / {@code agent_thought_chunk} text update. */
    static ObjectNode textChunk(String sessionUpdate, String text) {
        ObjectNode update = Util.MAPPER.createObjectNode();
        update.put("sessionUpdate", sessionUpdate);
        ObjectNode content = update.putObject("content");
        content.put("type", "text");
        content.put("text", text);
        return update;
    }

    /** A {@code tool_call} update (status {@code in_progress}), with the raw arguments as {@code rawInput}. */
    static ObjectNode toolCall(String toolName, String toolCallId, String argsJson) {
        ObjectNode update = Util.MAPPER.createObjectNode();
        update.put("sessionUpdate", "tool_call");
        update.put("toolCallId", toolCallId);
        update.put("title", toolName);
        update.put("kind", toolKind(toolName));
        update.put("status", "in_progress");
        JsonNode rawInput = parseArgs(argsJson);
        if (rawInput != null)
            update.set("rawInput", rawInput);
        return update;
    }

    /** A {@code tool_call_update} carrying the tool's result content and terminal status. */
    static ObjectNode toolResult(String toolCallId, String content, boolean isError) {
        ObjectNode update = Util.MAPPER.createObjectNode();
        update.put("sessionUpdate", "tool_call_update");
        update.put("toolCallId", toolCallId);
        update.put("status", isError ? "failed" : "completed");
        ArrayNode blocks = update.putArray("content");
        ObjectNode block = blocks.addObject();
        block.put("type", "content");
        ObjectNode inner = block.putObject("content");
        inner.put("type", "text");
        inner.put("text", content == null ? "" : content);
        return update;
    }

    /** An {@code available_commands_update} advertising the slash commands (names without a leading slash). */
    static ObjectNode availableCommands(List<SlashCommand> commands) {
        ObjectNode update = Util.MAPPER.createObjectNode();
        update.put("sessionUpdate", "available_commands_update");
        ArrayNode arr = update.putArray("availableCommands");
        for (SlashCommand c : commands) {
            ObjectNode cmd = arr.addObject();
            cmd.put("name", c.name());
            cmd.put("description", c.description() == null ? "" : c.description());
        }
        return update;
    }

    // ---- session modes ----

    /** One selectable agent operating mode (ACP {@code SessionMode}). */
    record Mode(String id, String name, String description) {
    }

    /** A {@code SessionModeState} (for the {@code session/new} response's {@code modes} field). */
    static ObjectNode sessionModes(String currentModeId, List<Mode> modes) {
        ObjectNode state = Util.MAPPER.createObjectNode();
        state.put("currentModeId", currentModeId);
        ArrayNode arr = state.putArray("availableModes");
        for (Mode mode : modes) {
            ObjectNode o = arr.addObject();
            o.put("id", mode.id());
            o.put("name", mode.name());
            if (mode.description() != null)
                o.put("description", mode.description());
        }
        return state;
    }

    /** A {@code current_mode_update} notifying the client the agent switched modes. */
    static ObjectNode currentModeUpdate(String currentModeId) {
        ObjectNode update = Util.MAPPER.createObjectNode();
        update.put("sessionUpdate", "current_mode_update");
        update.put("currentModeId", currentModeId);
        return update;
    }

    /** A {@code usage_update}: tokens currently in context ({@code used}) and the context window ({@code size}). */
    static ObjectNode usageUpdate(long used, long size) {
        ObjectNode update = Util.MAPPER.createObjectNode();
        update.put("sessionUpdate", "usage_update");
        update.put("used", used);
        update.put("size", size);
        return update;
    }

    // ---- permission ----

    /** The {@code session/request_permission} params for a mutating tool call (allow-once/always, reject-once). */
    static ObjectNode permissionRequest(String sessionId, String toolName, String toolCallId, String argsJson) {
        ObjectNode params = Util.MAPPER.createObjectNode();
        params.put("sessionId", sessionId);
        ObjectNode toolCall = params.putObject("toolCall");
        toolCall.put("toolCallId", toolCallId);
        toolCall.put("title", toolName);
        toolCall.put("kind", toolKind(toolName));
        JsonNode rawInput = parseArgs(argsJson);
        if (rawInput != null)
            toolCall.set("rawInput", rawInput);
        ArrayNode options = params.putArray("options");
        addOption(options, "allow_once", "Allow once");
        addOption(options, "allow_always", "Always allow " + toolName);
        addOption(options, "reject_once", "Reject");
        return params;
    }

    /** The selected {@code optionId} from a {@code session/request_permission} reply, or null if cancelled. */
    static String selectedOption(JsonNode res) {
        if (res == null || !"selected".equals(res.path("outcome").path("outcome").asText("")))
            return null;
        return res.path("outcome").path("optionId").asText(null);
    }

    private static void addOption(ArrayNode options, String id, String name) {
        ObjectNode o = options.addObject();
        o.put("optionId", id);
        o.put("name", name);
        o.put("kind", id); // ACP option kind mirrors the id here (allow_once/allow_always/reject_once)
    }

    // ---- client filesystem calls (fs/read_text_file, fs/write_text_file) ----

    static ObjectNode fsReadParams(String sessionId, String path) {
        ObjectNode params = Util.MAPPER.createObjectNode();
        params.put("sessionId", sessionId);
        params.put("path", path);
        return params;
    }

    static ObjectNode fsWriteParams(String sessionId, String path, String content) {
        ObjectNode params = Util.MAPPER.createObjectNode();
        params.put("sessionId", sessionId);
        params.put("path", path);
        params.put("content", content);
        return params;
    }

    // ---- prompt content blocks ----

    /** The text + attachments parsed out of an ACP prompt content-block array. */
    record PromptContent(String text, List<Message.Attachment> attachments) {
    }

    static PromptContent parsePrompt(JsonNode prompt) {
        List<Message.Attachment> attachments = new ArrayList<>();
        if (prompt == null || !prompt.isArray())
            return new PromptContent("", attachments);
        StringBuilder text = new StringBuilder();
        for (JsonNode block : prompt) {
            switch (block.path("type").asText("")) {
                case "text" -> append(text, block.path("text").asText(""));
                case "resource" -> append(text, block.path("resource").path("text").asText(""));
                case "resource_link" -> append(text, "@" + block.path("uri").asText(""));
                case "image" -> {
                    String data = block.path("data").asText("");
                    if (!data.isEmpty())
                        attachments.add(new Message.Attachment(block.path("mimeType").asText("image/png"), data, null));
                }
                default -> {} // unknown block types are ignored
            }
        }
        return new PromptContent(text.toString(), attachments);
    }

    private static void append(StringBuilder sb, String s) {
        if (s == null || s.isEmpty())
            return;
        if (sb.length() > 0)
            sb.append('\n');
        sb.append(s);
    }

    // ---- helpers ----

    /** Maps a pijota tool name to the closest ACP {@code ToolKind}. */
    static String toolKind(String toolName) {
        if (Util.SHELL_TOOL_NAMES.contains(toolName)) // bash + tmux_bash + jobs_run
            return "execute";
        return switch (toolName) {
            case "read", "ls" -> "read";
            case "write", "edit", "apply_patch" -> "edit";
            case "grep", "glob" -> "search";
            default -> "other";
        };
    }

    static JsonNode parseArgs(String json) {
        if (json == null || json.isBlank())
            return null;
        try {
            return Util.MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
