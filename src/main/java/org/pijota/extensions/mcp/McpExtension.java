package org.pijota.extensions.mcp;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionRuntime;
import org.pijota.messages.Message;
import org.pijota.modes.SlashCommand;
import org.pijota.tools.ToolRegistry;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges external MCP (Model Context Protocol) servers into pijota as tools. On {@link #onReady} it reads
 * {@code ~/.pijota/mcp.json} (+ project overrides), launches each configured server, performs the MCP handshake,
 * discovers its tools, and registers them in the live {@link ToolRegistry} (namespaced {@code mcp__<server>__<tool>}).
 * Tools are available to the model from the next turn. The extension is opt-in: with no config file it registers no
 * tools and spawns nothing.
 *
 * <p>
 * Servers are connected in parallel with a short bounded wait so fast servers are usable on the first turn while slow
 * ones (e.g. a cold {@code npx} download) register late without blocking startup. {@link #onShutdown} kills every
 * server process.
 *
 * <p>
 * The {@code /mcp} command manages the live set without restarting pijota: {@code list} (default),
 * {@code connect <name>}, {@code disconnect <name>} (kills the process and removes its tools),
 * {@code reconnect <name>}, and {@code reload} (re-reads the config and reconciles — connecting added servers,
 * disconnecting removed ones, restarting changed ones).
 */
public final class McpExtension implements Extension {

    private static final Logger log = LoggerFactory.getLogger(McpExtension.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);
    /** How long startup blocks for connections; stragglers register late (honored next turn). */
    private static final Duration STARTUP_WAIT = Duration.ofMillis(2500);

    private final Function<Path, List<McpServerConfig>> configLoader;
    private final McpTransport.Factory transports;
    private final Map<String, ServerState> servers = new ConcurrentHashMap<>();

    private ExtensionAPI api;
    private ExtensionRuntime runtime;
    // Two pools with distinct roles. lifecycle: a single thread that serializes every server mutation (startup
    // connects + all /mcp commands), so ServerState is never raced. completion: where McpClient resolves its async
    // request futures (handshake + tool calls) — kept separate so a connect() blocked awaiting a response can't
    // deadlock against its own completion.
    private volatile ExecutorService lifecycle;
    private volatile ExecutorService completion;

    public McpExtension() {
        this(McpConfig::load, StdioTransport::open);
    }

    /** Test seam: inject the server list and a fake transport so no real process is spawned. */
    McpExtension(Function<Path, List<McpServerConfig>> configLoader, McpTransport.Factory transports) {
        this.configLoader = configLoader;
        this.transports = transports;
    }

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public String description() {
        return "Bridges external MCP servers' tools into the session";
    }

    @Override
    public void init(ExtensionAPI api) {
        this.api = api;
        api.registerSlashCommand(new SlashCommand("mcp",
                                        "Manage MCP servers: list | connect | disconnect | reconnect <name> | reload",
                                        List.of("servers"), this::handleCommand));
        api.registerStatusLines(this::statusLines);
    }

    @Override
    public void onReady(ExtensionRuntime runtime) {
        this.runtime = runtime;
        var configs = configLoader.apply(runtime.cwd());
        if (configs.isEmpty())
            return;
        var registry = runtime.tools();
        var futures = new ArrayList<CompletableFuture<Void>>();
        for (var cfg : configs) {
            var state = new ServerState(cfg.name());
            servers.put(cfg.name(), state);
            futures.add(CompletableFuture.runAsync(() -> connect(cfg, state, registry), lifecycle()));
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(STARTUP_WAIT.toMillis(),
                                            TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // Slow/failed servers either register late or show as offline; never block startup on them.
        }
    }

    @Override
    public void onShutdown(ExtensionRuntime runtime) {
        if (lifecycle != null)
            lifecycle.shutdownNow(); // stop accepting new lifecycle work first
        for (var state : servers.values()) {
            McpClient client = state.client;
            if (client != null) {
                try {
                    client.close(); // kills the process; fails any pending calls (on the completion pool)
                } catch (Exception e) {
                    log.debug("MCP {} close failed: {}", state.name, e.getMessage());
                }
            }
        }
        if (completion != null)
            completion.shutdownNow();
    }

    private void connect(McpServerConfig cfg, ServerState state, ToolRegistry registry) {
        state.config = cfg;
        McpClient client = null;
        try {
            client = new McpClient(cfg.name(), transports.open(cfg, runtime.cwd()), completion());
            client.connect(CONNECT_TIMEOUT);
            List<McpToolDescriptor> tools = client.listTools(LIST_TIMEOUT);
            for (var descriptor : tools) {
                var def = McpToolBridge.toDefinition(cfg.name(), descriptor, client, CALL_TIMEOUT);
                registry.register(def.toTool());
                state.toolNames.add(def.name());
            }
            state.client = client;
            state.error = null;
            log.info("MCP server {} connected: {} tool(s)", cfg.name(), tools.size());
        } catch (Exception e) {
            if (client != null)
                client.close(); // don't leak the process + reader thread when the handshake/listing fails
            state.error = e.getMessage();
            log.warn("MCP server {} failed: {}", cfg.name(), e.getMessage());
        }
    }

    private void handleCommand(String input) {
        String[] parts = (input == null ? "" : input.trim()).split("\\s+", 2);
        String verb = parts[0];
        String target = parts.length > 1 ? parts[1].trim() : "";
        switch (verb) {
            case "" -> list();
            case "connect" -> lifecycle().execute(() -> connectCommand(target));
            case "disconnect" -> lifecycle().execute(() -> disconnectCommand(target));
            case "reconnect" -> lifecycle().execute(() -> reconnectCommand(target));
            case "reload" -> lifecycle().execute(this::reloadCommand);
            default -> runtime.setStatus("MCP: usage — /mcp [connect|disconnect|reconnect <name>|reload]");
        }
    }

    private void list() {
        if (servers.isEmpty()) {
            runtime.setStatus("No MCP servers configured (~/.pijota/mcp.json)");
            return;
        }
        var sb = new StringBuilder("MCP servers\n");
        for (var state : servers.values()) {
            boolean up = state.connected();
            sb.append(up ? "  ✓ " : "  ✗ ").append(state.name);
            if (up)
                sb.append("  ").append(state.toolNames.size()).append(" tool(s)");
            else if (state.error != null)
                sb.append("  offline: ").append(state.error); // failed to connect
            else if (state.client != null)
                sb.append("  offline (died)"); // connected, then the process exited
            else
                sb.append("  idle"); // never started, or user-disconnected
            sb.append('\n');
        }
        sb.append("\n  /mcp connect|disconnect|reconnect <name> · /mcp reload");
        api.sendMessage(new Message.Custom("mcp", sb.toString()));
    }

    private void connectCommand(String name) {
        var state = require(name);
        if (state == null)
            return;
        if (state.connected()) {
            runtime.setStatus("MCP: " + name + " already connected");
            return;
        }
        runtime.setStatus("MCP: connecting " + name + "…");
        connectAndReport(state);
    }

    private void disconnectCommand(String name) {
        var state = require(name);
        if (state == null)
            return;
        closeAndUnregister(state);
        runtime.setStatus("MCP: disconnected " + name);
    }

    private void reconnectCommand(String name) {
        var state = require(name);
        if (state == null)
            return;
        runtime.setStatus("MCP: reconnecting " + name + "…");
        closeAndUnregister(state);
        connectAndReport(state);
    }

    /** Looks up a server by name, reporting "unknown server" to the status line (and returning null) if absent. */
    private ServerState require(String name) {
        var state = servers.get(name);
        if (state == null)
            runtime.setStatus("MCP: unknown server '" + name + "' (try /mcp reload)");
        return state;
    }

    /** (Re)connects an existing server using its last config and reports the resulting state. */
    private void connectAndReport(ServerState state) {
        connect(state.config, state, runtime.tools());
        runtime.setStatus("MCP: " + state.name + (state.connected() ? " connected" : " failed"));
    }

    /** Re-reads the config and reconciles the live set with it (add new, drop removed, restart changed). */
    private void reloadCommand() {
        var registry = runtime.tools();
        var desired = new LinkedHashMap<String, McpServerConfig>();
        for (var cfg : configLoader.apply(runtime.cwd()))
            desired.put(cfg.name(), cfg);
        int added = 0, removed = 0, updated = 0;
        for (var name : List.copyOf(servers.keySet())) {
            if (!desired.containsKey(name)) {
                closeAndUnregister(servers.remove(name));
                removed++;
            }
        }
        for (var cfg : desired.values()) {
            var state = servers.get(cfg.name());
            if (state == null) {
                state = new ServerState(cfg.name());
                servers.put(cfg.name(), state);
                connect(cfg, state, registry);
                added++;
            } else if (!cfg.equals(state.config)) {
                closeAndUnregister(state);
                connect(cfg, state, registry);
                updated++;
            }
        }
        runtime.setStatus("MCP reload: +" + added + " -" + removed + " ~" + updated);
    }

    /** Stops a server's process (failing in-flight calls) and removes its tools from the registry. */
    private void closeAndUnregister(ServerState state) {
        var registry = runtime.tools();
        McpClient client = state.client;
        if (client != null)
            client.close();
        for (String toolName : state.toolNames)
            registry.unregister(toolName);
        state.toolNames.clear();
        state.client = null;
    }

    /** Single thread that serializes all server mutations (startup connects and /mcp commands). */
    private synchronized ExecutorService lifecycle() {
        if (lifecycle == null)
            lifecycle = Executors.newSingleThreadExecutor(daemonThreads("mcp-lifecycle"));
        return lifecycle;
    }

    /** Concurrent pool where clients resolve request futures (handshake responses + tool-call results). */
    private synchronized ExecutorService completion() {
        if (completion == null)
            completion = Executors.newCachedThreadPool(daemonThreads("mcp"));
        return completion;
    }

    private List<String> statusLines() {
        if (servers.isEmpty())
            return List.of();
        var lines = new ArrayList<String>();
        for (var state : servers.values()) {
            if (state.connected())
                lines.add("🔌 mcp:" + state.name + "  ✓ " + state.toolNames.size() + " tools");
            else
                lines.add("🔌 mcp:" + state.name + "  ✗ offline");
        }
        return lines;
    }

    private static ThreadFactory daemonThreads(String prefix) {
        var counter = new AtomicInteger();
        return r -> {
            var t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Mutable per-server connection state (visibility via volatile; tool list is a concurrent collection). */
    private static final class ServerState {
        final String name;
        final List<String> toolNames = new CopyOnWriteArrayList<>();
        volatile McpServerConfig config; // the config last used to connect (for reconnect + reload diffing)
        volatile McpClient client;
        volatile String error;

        ServerState(String name) {
            this.name = name;
        }

        /** Live connection state, derived from the client — so a server that died mid-session reads as offline. */
        boolean connected() {
            return client != null && client.alive();
        }
    }
}
