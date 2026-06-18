package ai.pijota.extensions.tmux;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.ExtensionContext;
import ai.pijota.extensions.ExtensionEvent;
import ai.pijota.extensions.ExtensionResult;
import ai.pijota.extensions.ExtensionRuntime;
import ai.pijota.extensions.UiContext;
import ai.pijota.llm.ToolSchema;
import ai.pijota.messages.Message;
import ai.pijota.modes.SlashCommand;
import ai.pijota.tools.Tool;
import ai.pijota.util.Emojis;
import ai.pijota.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Tmux integration for Pijota. Allows the agent and the user to run long-lived shell processes outside the synchronous
 * {@code bash} tool, capture their output later, and supervise background tasks. Opt-in via {@code --tmux}.
 */
public final class TmuxExtension implements Extension {

    private static final String CUSTOM_TYPE = "tmux";
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Pattern FORBIDDEN_KEYS = Pattern.compile("[\\p{Cntrl}]");
    private static final int MAX_OUTPUT_LINES = 1000;
    private static final int MAX_STATUS_NAMES = 3;

    private final TmuxClient client;
    private final Map<String, TmuxSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(0);
    private ExtensionAPI api;
    private volatile String sessionPrefix;

    public TmuxExtension() {
        this(new TmuxClient());
    }

    TmuxExtension(TmuxClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "tmux";
    }

    @Override
    public String description() {
        return "Run and supervise long-lived shell processes via tmux (--tmux)";
    }

    @Override
    public void init(ExtensionAPI api) {
        this.api = api;

        api.on(ExtensionEvent.SessionStart.class, (ev, _ctx) -> {
            String fileName = ev.sessionPath() != null ? ev.sessionPath().getFileName().toString() : null;
            String base = fileName != null ? fileName.replaceFirst("\\.[^.]+$", "") : Util.shortUuid(8);
            this.sessionPrefix = "pijota-" + base.substring(0, Math.min(base.length(), 8));
        });

        api.on(ExtensionEvent.SessionReady.class, (_, _ctx) -> loadState());

        api.registerMessageRenderer(this::renderCustomMessage);

        api.registerTool(new Tool.ToolDefinition("tmux_bash",
                                        "Run a shell command in a new detached tmux pane and return immediately. "
                                                                        + "Use this for long-running commands (dev servers, tests, log tailing) "
                                                                        + "that would otherwise time out in the synchronous bash tool.",
                                        ToolSchema.object().str("command", "Shell command to run", true).str("workdir",
                                                                        "Working directory (default: session cwd)")
                                                                        .str("name", "Friendly name for the session (auto-generated if omitted)")
                                                                        .bool("cleanup_on_exit", "Kill the tmux session when the process exits (default false)")
                                                                        .build(),
                                        (id, args, cwd) -> CompletableFuture.completedFuture(tmuxBash(args, cwd))));

        api.registerTool(new Tool.ToolDefinition("tmux_send",
                                        "Send keystrokes to a running tmux pane. Special keys: C-c, C-z, Enter.",
                                        ToolSchema.object().str("target", "Friendly name or tmux target", true)
                                                                        .str("keys", "Keys to send", true)
                                                                        .bool("enter", "Append Enter (default true)")
                                                                        .build(),
                                        (id, args, cwd) -> CompletableFuture.completedFuture(tmuxSend(args))));

        api.registerTool(new Tool.ToolDefinition("tmux_capture", "Capture recent output from a tmux pane.", ToolSchema
                                        .object().str("target", "Friendly name or tmux target", true)
                                        .integer("lines", "Number of lines to capture (default 100, max 1000)").build(),
                                        (id, args, cwd) -> CompletableFuture.completedFuture(tmuxCapture(args))));

        api.registerTool(new Tool.ToolDefinition("tmux_list",
                                        "List active tmux sessions managed by Pijota, or all tmux panes in a session.",
                                        ToolSchema.object().str("scope", "sessions or panes (default sessions)")
                                                                        .str("session", "Session name when scope=panes")
                                                                        .build(),
                                        (id, args, cwd) -> CompletableFuture.completedFuture(tmuxList(args))));

        api.registerTool(new Tool.ToolDefinition("tmux_kill", "Kill a tmux session or pane managed by Pijota.",
                                        ToolSchema.object().str("target", "Friendly name or tmux target", true).bool(
                                                                        "confirm",
                                                                        "Confirmation already obtained (default false)")
                                                                        .build(),
                                        (id, args, cwd) -> CompletableFuture.completedFuture(tmuxKill(args, false))));

        api.registerSlashCommand(new SlashCommand("tmux-list", "List supervised tmux sessions", List.of(),
                                        _args -> note(listText())));
        api.registerSlashCommand(new SlashCommand("tmux-send", "Send keys to a tmux pane: /tmux-send <target> <keys>",
                                        List.of(), this::slashSend));
        api.registerSlashCommand(new SlashCommand("tmux-capture",
                                        "Capture output from a tmux pane: /tmux-capture <target>", List.of(),
                                        this::slashCapture));
        api.registerSlashCommand(new SlashCommand("supervise",
                                        "Run a command in tmux and track it: /supervise <command>", List.of(),
                                        this::slashSupervise));

        api.registerStatusLines(this::statusLines);
    }

    @Override
    public void onShutdown(ExtensionRuntime runtime) {
        for (var session : sessions.values()) {
            if (session.autoCleanup())
                client.killSession(session.tmuxName());
        }
    }

    // ---- tools ----

    private Tool.ToolResult tmuxBash(Map<String, Object> args, String cwd) {
        if (!client.hasTmux())
            return Tool.ToolResult.error("tmux is not installed");

        String command = Util.getString(args, "command");
        if (command == null || command.isBlank())
            return Tool.ToolResult.error("command is required");

        Path workdir = resolveWorkdir(args, cwd);
        String friendlyName = uniqueFriendlyName(Util.getString(args, "name", generateName(command)));
        String tmuxName = tmuxName(friendlyName);
        boolean cleanup = Boolean.TRUE.equals(args.get("cleanup_on_exit"));

        var result = client.newSession(tmuxName, workdir, command);
        if (!result.success())
            return Tool.ToolResult.error("tmux new-session failed: " + result.stderr());

        String paneId = findFirstPane(tmuxName);
        var session = new TmuxSession(Util.shortUuid(8), tmuxName, friendlyName, command,
                                        workdir != null ? workdir.toString() : cwd, paneId, System.currentTimeMillis(),
                                        cleanup);
        sessions.put(friendlyName, session);
        saveState();

        Map<String, Object> details = Map.<String, Object>of("name", friendlyName, "tmux_name", tmuxName, "pane",
                                        paneId != null ? paneId : "", "command", command);
        return Tool.ToolResult.success("Started tmux session \"" + friendlyName + "\"", details);
    }

    private Tool.ToolResult tmuxSend(Map<String, Object> args) {
        if (!client.hasTmux())
            return Tool.ToolResult.error("tmux is not installed");

        String target = resolveTarget(Util.getString(args, "target"));
        String keys = Util.getString(args, "keys");
        if (target == null)
            return Tool.ToolResult.error("target is required");
        if (keys == null || keys.isBlank())
            return Tool.ToolResult.error("keys is required");
        if (FORBIDDEN_KEYS.matcher(keys).find())
            return Tool.ToolResult.error("keys contain control characters");

        boolean enter = !Boolean.FALSE.equals(args.get("enter"));
        var result = client.sendKeys(target, keys, enter);
        if (!result.success())
            return Tool.ToolResult.error("tmux send-keys failed: " + result.stderr());
        return Tool.ToolResult.success("Sent keys to " + target);
    }

    private Tool.ToolResult tmuxCapture(Map<String, Object> args) {
        if (!client.hasTmux())
            return Tool.ToolResult.error("tmux is not installed");

        String target = resolveTarget(Util.getString(args, "target"));
        if (target == null)
            return Tool.ToolResult.error("target is required");

        int lines = (int) Util.getLong(args, "lines", 100);
        lines = Math.max(1, Math.min(lines, MAX_OUTPUT_LINES));

        var result = client.capturePane(target, lines);
        if (!result.success())
            return Tool.ToolResult.error("tmux capture-pane failed: " + result.stderr());

        String output = result.stdout();
        if (output.isBlank())
            return Tool.ToolResult.success("(no output)");
        return Tool.ToolResult.success(output);
    }

    private Tool.ToolResult tmuxList(Map<String, Object> args) {
        if (!client.hasTmux())
            return Tool.ToolResult.error("tmux is not installed");

        String scope = Util.getString(args, "scope", "sessions");
        if ("panes".equals(scope)) {
            String session = Util.getString(args, "session");
            if (session == null)
                return Tool.ToolResult.error("session is required when scope=panes");
            var result = client.listPanes(session);
            if (!result.success())
                return Tool.ToolResult.error("tmux list-panes failed: " + result.stderr());
            return Tool.ToolResult.success(result.stdout().isBlank() ? "(no panes)" : result.stdout());
        }

        refreshAlive();
        return Tool.ToolResult.success(listText());
    }

    private Tool.ToolResult tmuxKill(Map<String, Object> args, boolean fromSlash) {
        if (!client.hasTmux())
            return Tool.ToolResult.error("tmux is not installed");

        String target = Util.getString(args, "target");
        if (target == null)
            return Tool.ToolResult.error("target is required");

        var session = sessions.values().stream().filter(s -> s.friendlyName().equals(target)).findFirst().orElse(null);
        String tmuxTarget = session != null ? session.tmuxName() : target;

        if (fromSlash) {
            boolean ok = uiConfirm("Kill tmux session " + tmuxTarget + "?");
            if (!ok)
                return Tool.ToolResult.success("Cancelled");
        }

        var result = client.killSession(tmuxTarget);
        if (!result.success() && !result.stderr().contains("session not found"))
            return Tool.ToolResult.error("tmux kill-session failed: " + result.stderr());

        if (session != null) {
            sessions.put(session.friendlyName(), session.withAlive(false));
            saveState();
        }
        return Tool.ToolResult.success("Killed " + tmuxTarget);
    }

    // ---- slash commands ----

    private void slashSend(String args) {
        if (args == null || args.isBlank()) {
            note("Usage: /tmux-send <target> <keys>");
            return;
        }
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) {
            note("Usage: /tmux-send <target> <keys>");
            return;
        }
        var result = tmuxSend(Map.of("target", parts[0], "keys", parts[1], "enter", true));
        note(result.content());
    }

    private void slashCapture(String args) {
        String target = args != null ? args.strip() : "";
        if (target.isBlank()) {
            note("Usage: /tmux-capture <target>");
            return;
        }
        var result = tmuxCapture(Map.of("target", target, "lines", 200));
        String text = result.content();
        if (text == null || text.isBlank())
            text = "(no output)";
        note(text);
    }

    private void slashSupervise(String args) {
        String command = args != null ? args.strip() : "";
        if (command.isBlank()) {
            note("Usage: /supervise <command>");
            return;
        }
        var result = tmuxBash(Map.of("command", command, "name", generateName(command)),
                                        api.runtime().cwd().toString());
        note(result.content());
    }

    // ---- state persistence ----

    private void saveState() {
        var sb = new StringBuilder();
        for (var session : sessions.values()) {
            sb.append(String.join("\t", session.id(), session.tmuxName(), session.friendlyName(), session.command(),
                                            session.workdir() != null ? session.workdir() : "",
                                            session.paneId() != null ? session.paneId() : "",
                                            String.valueOf(session.createdAt()), String.valueOf(session.autoCleanup()),
                                            String.valueOf(session.alive())));
            sb.append('\n');
        }
        api.sendMessage(new Message.Custom(CUSTOM_TYPE, sb.toString()));
    }

    private void loadState() {
        sessions.clear();
        List<Message> messages = api.runtime().messages();
        if (messages == null)
            return;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof Message.Custom c && CUSTOM_TYPE.equals(c.customType())) {
                for (String line : c.content().lines().toList()) {
                    if (line.isBlank())
                        continue;
                    String[] parts = line.split("\t", 9);
                    if (parts.length < 8)
                        continue;
                    var session = new TmuxSession(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5],
                                                    parseLong(parts[6]), Boolean.parseBoolean(parts[7]));
                    sessions.putIfAbsent(session.friendlyName(), session);
                }
                refreshAlive();
                return;
            }
        }
    }

    private void refreshAlive() {
        if (!client.hasTmux())
            return;
        for (var entry : sessions.entrySet()) {
            var session = entry.getValue();
            boolean alive = client.sessionExists(session.tmuxName());
            if (alive != session.alive()) {
                entry.setValue(session.withAlive(alive));
            }
        }
    }

    // ---- rendering ----

    private ExtensionResult.MessageRender renderCustomMessage(Message msg, ExtensionContext ctx) {
        if (!(msg instanceof Message.Custom c) || !CUSTOM_TYPE.equals(c.customType()))
            return null;
        var lines = new ArrayList<String>();
        for (String line : c.content().lines().toList()) {
            if (line.isBlank())
                continue;
            String[] parts = line.split("\t", 9);
            if (parts.length >= 3) {
                boolean alive = parts.length >= 9 && Boolean.parseBoolean(parts[8]);
                lines.add((alive ? "▣" : "□") + " " + parts[2] + " — " + parts[3]);
            }
        }
        if (lines.isEmpty())
            return new ExtensionResult.MessageRender(List.of("no tmux sessions"));
        return new ExtensionResult.MessageRender(lines);
    }

    private List<String> statusLines() {
        refreshAlive();
        var alive = sessions.values().stream().filter(TmuxSession::alive)
                                        .sorted(Comparator.comparing(TmuxSession::createdAt)).toList();
        if (alive.isEmpty())
            return List.of();
        var names = new ArrayList<String>();
        for (int i = 0; i < Math.min(alive.size(), MAX_STATUS_NAMES); i++)
            names.add(alive.get(i).friendlyName());
        String more = alive.size() > MAX_STATUS_NAMES ? " +" + (alive.size() - MAX_STATUS_NAMES) : "";
        return List.of(Emojis.PLAY + " " + alive.size() + " tmux: " + String.join(", ", names) + more);
    }

    private String listText() {
        refreshAlive();
        if (sessions.isEmpty())
            return "No supervised tmux sessions.";
        var sb = new StringBuilder();
        sessions.values().stream().sorted(Comparator.comparing(TmuxSession::createdAt)).forEach(s -> {
            sb.append(String.format("%s %-20s pane:%-8s cmd:%s%s%n", s.alive() ? "▣" : "□", s.friendlyName(),
                                            s.paneId() != null ? s.paneId() : "-", s.command(),
                                            s.autoCleanup() ? " [cleanup]" : ""));
        });
        return sb.toString().strip();
    }

    // ---- helpers ----

    private Path resolveWorkdir(Map<String, Object> args, String cwd) {
        String wd = Util.getString(args, "workdir");
        Path path = wd != null ? Path.of(wd) : (cwd != null ? Path.of(cwd) : Path.of(System.getProperty("user.dir")));
        return Files.isDirectory(path) ? path : null;
    }

    private String tmuxName(String friendlyName) {
        return sessionPrefix + "-" + friendlyName;
    }

    private String uniqueFriendlyName(String base) {
        String name = sanitizeName(base);
        if (!sessions.containsKey(name))
            return name;
        int i = 2;
        while (sessions.containsKey(name + "-" + i))
            i++;
        return name + "-" + i;
    }

    private String generateName(String command) {
        String first = command.strip().split("\\s+", 2)[0];
        String base = Path.of(first).getFileName().toString();
        return base + "-" + sequence.incrementAndGet();
    }

    private String sanitizeName(String name) {
        String s = name.replaceAll("[^a-zA-Z0-9._-]", "-").replaceAll("^-+|-+$", "");
        if (s.isBlank())
            s = "session";
        return s.substring(0, Math.min(s.length(), 48));
    }

    private String resolveTarget(String target) {
        if (target == null)
            return null;
        var session = sessions.get(target);
        if (session != null) {
            if (session.paneId() != null && !session.paneId().isBlank())
                return session.paneId();
            return session.tmuxName();
        }
        return target;
    }

    private String findFirstPane(String sessionName) {
        var result = client.listPanes(sessionName);
        if (!result.success())
            return null;
        for (String line : result.stdout().lines().toList()) {
            if (line.isBlank())
                continue;
            String[] parts = line.split("\t", 3);
            if (parts.length > 0 && parts[0].startsWith("%"))
                return parts[0];
        }
        return null;
    }

    private boolean uiConfirm(String question) {
        UiContext ui = api.runtime().ui();
        if (ui == null)
            return false;
        try {
            return ui.confirm(question).join();
        } catch (Exception e) {
            return false;
        }
    }

    private void note(String text) {
        api.sendMessage(new Message.Custom(CUSTOM_TYPE + "-note", text));
    }

    private static long parseLong(String s) {
        try {
            return s != null && !s.isBlank() ? Long.parseLong(s) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
