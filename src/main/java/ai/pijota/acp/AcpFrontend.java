package ai.pijota.acp;

import ai.pijota.agent.AgentEvent;
import ai.pijota.agent.PijotaSession;
import ai.pijota.extensions.UiContext;
import ai.pijota.llm.ThinkingLevel;
import ai.pijota.messages.Message;
import ai.pijota.modes.SlashCommand;
import ai.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * ACP (Agent Client Protocol) frontend — lets an ACP client (Zed, IntelliJ/JetBrains AI Assistant, Neovim, …) drive
 * pijota over stdio. It owns the JSON-RPC transport ({@link AcpPeer}), answers the {@code initialize → session/new →
 * session/prompt} lifecycle, drives {@link PijotaSession#prompt}, and mirrors the session's {@link AgentEvent} stream
 * out as {@code session/update} notifications — the same "subscribe to events, translate to the wire" shape as the web
 * and Telegram frontends.
 *
 * <p>
 * Collaborators: {@link AcpPeer} (JSON-RPC transport), {@link AcpUiContext} (the extension {@code UiContext}), and
 * {@link AcpPermissionExtension} (which gates mutating tools through {@link #requestToolPermission}). This frontend
 * keeps lifecycle, streaming translation, and slash-command dispatch.
 *
 * <p>
 * Not yet wired: {@code fs/*} and {@code terminal/*} client routing, {@code session/load}, and multiple concurrent
 * sessions — those layer on as follow-ups. v1 serves a single session per connection (the bootstrap session).
 */
public final class AcpFrontend {

    /** Highest ACP protocol version this agent implements. */
    private static final int PROTOCOL_VERSION = 1;

    /** ACP session modes ↔ pijota thinking levels (a legitimate agent operating mode). */
    private static final List<AcpMessages.Mode> THINKING_MODES = List.of(
                                    new AcpMessages.Mode("off", "Off", "No extended thinking"),
                                    new AcpMessages.Mode("minimal", "Minimal", "Minimal thinking budget"),
                                    new AcpMessages.Mode("low", "Low", "Low thinking budget"),
                                    new AcpMessages.Mode("medium", "Medium", "Medium thinking budget"),
                                    new AcpMessages.Mode("high", "High", "High thinking budget"));

    private final PijotaSession session;
    private final List<SlashCommand> commands;
    private final AcpPeer peer;
    private final AcpUiContext uiContext = new AcpUiContext(this::emit);

    private volatile String sessionId;
    private volatile boolean cancelled;
    private volatile ThinkingLevel thinkingLevel;
    private volatile boolean fsReadEnabled;
    private volatile boolean fsWriteEnabled;
    private Consumer<AgentEvent> listener;

    /** Tools the user chose "always allow" for this connection, so we stop prompting for them. */
    private final Set<String> alwaysAllowedTools = ConcurrentHashMap.newKeySet();

    public AcpFrontend(PijotaSession session, List<SlashCommand> commands, ThinkingLevel thinkingLevel) {
        this.session = session;
        this.commands = List.copyOf(commands);
        this.thinkingLevel = thinkingLevel != null ? thinkingLevel : ThinkingLevel.OFF;
        this.peer = new AcpPeer(System.in, System.out);
    }

    /** {@link UiContext} that relays command/status output to the ACP client as assistant message chunks. */
    public UiContext ui() {
        return uiContext;
    }

    /** Wires the protocol handlers and reads frames until the client closes stdin. */
    public void run() throws Exception {
        peer.onRequest("initialize", this::onInitialize).onRequest("authenticate",
                                        p -> CompletableFuture.completedFuture(Util.MAPPER.createObjectNode()))
                                        .onRequest("session/new", this::onNewSession)
                                        .onRequest("session/prompt", this::onPrompt)
                                        .onRequest("session/set_mode", this::onSetMode)
                                        .onNotification("session/cancel", this::onCancel)
                                        .onResponded(this::afterResponded);
        peer.run();
    }

    // ---- request handlers ----

    private CompletableFuture<JsonNode> onInitialize(JsonNode params) {
        JsonNode fs = params.path("clientCapabilities").path("fs");
        fsReadEnabled = fs.path("readTextFile").asBoolean(false);
        fsWriteEnabled = fs.path("writeTextFile").asBoolean(false);
        int requested = params.path("protocolVersion").asInt(PROTOCOL_VERSION);
        return CompletableFuture.completedFuture(AcpMessages.initializeResult(Math.min(requested, PROTOCOL_VERSION)));
    }

    // ---- client filesystem (fs/read_text_file, fs/write_text_file), gated on the client's advertised capability ----

    boolean fsReadEnabled() {
        return fsReadEnabled;
    }

    boolean fsWriteEnabled() {
        return fsWriteEnabled;
    }

    /** Asks the client to read a file (its editor view, incl. unsaved buffers), resolving to the text content. */
    CompletableFuture<String> fsReadTextFile(String absolutePath) {
        return peer.request("fs/read_text_file", AcpMessages.fsReadParams(sessionId, absolutePath))
                                        .thenApply(res -> res.path("content").asText(""));
    }

    /** Asks the client to write a file so its editor reflects the change. */
    CompletableFuture<Void> fsWriteTextFile(String absolutePath, String content) {
        return peer.request("fs/write_text_file", AcpMessages.fsWriteParams(sessionId, absolutePath, content))
                                        .thenApply(res -> null);
    }

    private CompletableFuture<JsonNode> onNewSession(JsonNode params) {
        // v1: one session per process. Mint an id and (re)attach the streaming bridge to the bootstrap session.
        String sid = UUID.randomUUID().toString();
        this.sessionId = sid;
        attachStreaming(sid);
        ObjectNode result = Util.MAPPER.createObjectNode();
        result.put("sessionId", sid);
        result.set("modes", AcpMessages.sessionModes(thinkingLevel.name().toLowerCase(), THINKING_MODES));
        // Commands are advertised in afterResponded(), strictly after this response is written — a session/update that
        // arrives before the client has registered the session id is dropped (why Zed showed no commands).
        return CompletableFuture.completedFuture(result);
    }

    /** {@code session/set_mode}: the client picked an operating mode → apply it as pijota's thinking level. */
    private CompletableFuture<JsonNode> onSetMode(JsonNode params) {
        ThinkingLevel level = ThinkingLevel.fromString(params.path("modeId").asText(""));
        thinkingLevel = level;
        session.setThinkingLevel(level);
        return CompletableFuture.completedFuture(Util.MAPPER.createObjectNode());
    }

    /** Runs after a request's response is written — sends the follow-ups that must be ordered after it. */
    private void afterResponded(String method) {
        if ("session/new".equals(method))
            advertiseCommands(sessionId);
    }

    /** Publishes pijota's slash commands so the client (e.g. Zed) can offer them in its {@code /} menu. */
    private void advertiseCommands(String sid) {
        if (!commands.isEmpty())
            sendUpdate(sid, AcpMessages.availableCommands(commands));
    }

    private CompletableFuture<JsonNode> onPrompt(JsonNode params) {
        String sid = params.path("sessionId").asText(null);
        if (sid == null || !sid.equals(sessionId))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown sessionId: " + sid));

        AcpMessages.PromptContent content = AcpMessages.parsePrompt(params.path("prompt"));
        List<Message.Attachment> attachments = content.attachments();

        cancelled = false;
        String trimmed = content.text().strip();

        // A prior select/confirm may be waiting for this message as its answer.
        if (uiContext.tryAnswer(trimmed))
            return CompletableFuture.completedFuture(stopReason());

        if (trimmed.startsWith("/") && trimmed.length() > 1 && attachments.isEmpty())
            return runCommand(sid, trimmed);

        return session.prompt(content.text(), attachments).thenApply(messages -> stopReason());
    }

    /** Runs a slash command locally (not sent to the LLM), relaying its output back as message chunks. */
    private CompletableFuture<JsonNode> runCommand(String sid, String line) {
        SlashCommand cmd = SlashCommand.find(commands, line);
        if (cmd == null) {
            emit("Unknown command: " + line.split("\\s+", 2)[0]);
            return CompletableFuture.completedFuture(stopReason());
        }
        String args = SlashCommand.argsOf(line);
        // Handlers may block (some open pickers or do IO), so run off the read loop.
        return CompletableFuture.runAsync(() -> cmd.handler().accept(args)).handle((v, err) -> {
            if (err != null)
                emit("Command failed: " + err.getMessage());
            return stopReason();
        });
    }

    private JsonNode stopReason() {
        ObjectNode result = Util.MAPPER.createObjectNode();
        result.put("stopReason", cancelled ? "cancelled" : "end_turn");
        return result;
    }

    private void onCancel(JsonNode ignored) {
        cancelled = true;
        uiContext.cancelPending(); // release any confirm/select blocking a tool so the turn can't hang
        session.abort();
    }

    // ---- permission / interaction (session/request_permission) ----

    /**
     * Asks the client to approve a mutating tool call. Resolves true (allow) / false (reject). Remembers "always allow"
     * per tool for the rest of the connection. Fails open on a transport error so a client that mishandles the request
     * can't wedge the agent.
     */
    public CompletableFuture<Boolean> requestToolPermission(String toolName, String toolCallId, String argsJson) {
        String sid = this.sessionId;
        if (sid == null || alwaysAllowedTools.contains(toolName))
            return CompletableFuture.completedFuture(true);
        var params = AcpMessages.permissionRequest(sid, toolName, toolCallId, argsJson);
        return peer.request("session/request_permission", params).thenApply(res -> {
            String opt = AcpMessages.selectedOption(res);
            if ("allow_always".equals(opt)) {
                alwaysAllowedTools.add(toolName);
                return true;
            }
            return "allow_once".equals(opt);
        }).exceptionally(e -> true); // fail open: a broken round-trip must not wedge the agent
    }

    /** Sends a line of agent-visible text to the current session as an {@code agent_message_chunk}. */
    private void emit(String text) {
        String sid = this.sessionId;
        if (sid == null || text == null || text.isEmpty())
            return;
        sendUpdate(sid, AcpMessages.textChunk("agent_message_chunk", text.endsWith("\n") ? text : text + "\n"));
    }

    // ---- streaming: AgentEvent -> session/update ----

    private void attachStreaming(String sid) {
        if (listener != null)
            session.removeEventListener(listener);
        var stream = new AcpStream(session::estimatedContextTokens); // fresh per session (holds the thinking prefix)
        listener = event -> stream.translate(event).forEach(update -> sendUpdate(sid, update));
        session.addEventListener(listener);
    }

    /** Wraps an {@link AcpMessages} update payload with the live session id and notifies the client. */
    private void sendUpdate(String sid, ObjectNode update) {
        ObjectNode params = Util.MAPPER.createObjectNode();
        params.put("sessionId", sid);
        params.set("update", update);
        peer.notify("session/update", params);
    }
}
