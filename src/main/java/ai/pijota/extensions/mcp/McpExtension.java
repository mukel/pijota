package ai.pijota.extensions.mcp;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.ExtensionRuntime;
import ai.pijota.messages.Message;
import ai.pijota.modes.SlashCommand;
import ai.pijota.tools.ToolRegistry;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    private volatile ExecutorService executor;

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
        api.registerSlashCommand(new SlashCommand("mcp", "List MCP servers (or: reconnect <name>)", List.of("servers"),
                                        this::handleCommand));
        api.registerStatusLines(this::statusLines);
    }

    @Override
    public void onReady(ExtensionRuntime runtime) {
        this.runtime = runtime;
        var configs = configLoader.apply(runtime.cwd());
        if (configs.isEmpty())
            return;
        this.executor = Executors.newCachedThreadPool(daemonThreads("mcp"));
        var registry = runtime.tools();
        var futures = new ArrayList<CompletableFuture<Void>>();
        for (var cfg : configs) {
            var state = new ServerState(cfg.name());
            servers.put(cfg.name(), state);
            futures.add(CompletableFuture.runAsync(() -> connect(cfg, state, registry), executor));
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
        for (var state : servers.values()) {
            McpClient client = state.client;
            if (client != null) {
                try {
                    client.close();
                } catch (Exception e) {
                    log.debug("MCP {} close failed: {}", state.name, e.getMessage());
                }
            }
        }
        if (executor != null)
            executor.shutdownNow();
    }

    private void connect(McpServerConfig cfg, ServerState state, ToolRegistry registry) {
        try {
            var transport = transports.open(cfg, runtime.cwd());
            var client = new McpClient(cfg.name(), transport, executor);
            client.connect(CONNECT_TIMEOUT);
            List<McpToolDescriptor> tools = client.listTools(LIST_TIMEOUT);
            for (var descriptor : tools) {
                var def = McpToolBridge.toDefinition(cfg.name(), descriptor, client, CALL_TIMEOUT);
                registry.register(def.toTool());
                state.toolNames.add(def.name());
            }
            state.client = client;
            state.connected = true;
            state.error = null;
            log.info("MCP server {} connected: {} tool(s)", cfg.name(), tools.size());
        } catch (Exception e) {
            state.connected = false;
            state.error = e.getMessage();
            log.warn("MCP server {} failed: {}", cfg.name(), e.getMessage());
        }
    }

    private void handleCommand(String input) {
        String arg = input == null ? "" : input.trim();
        if (arg.startsWith("reconnect")) {
            String target = arg.substring("reconnect".length()).trim();
            reconnect(target);
            return;
        }
        if (servers.isEmpty()) {
            runtime.setStatus("No MCP servers configured (~/.pijota/mcp.json)");
            return;
        }
        var sb = new StringBuilder("MCP servers\n");
        for (var state : servers.values()) {
            sb.append(state.connected ? "  ✓ " : "  ✗ ").append(state.name);
            if (state.connected)
                sb.append("  ").append(state.toolNames.size()).append(" tool(s)");
            else if (state.error != null)
                sb.append("  offline: ").append(state.error);
            else
                sb.append("  offline");
            sb.append('\n');
        }
        sb.append("\n  /mcp reconnect <name>");
        api.sendMessage(new Message.Custom("mcp", sb.toString()));
    }

    private void reconnect(String target) {
        var state = servers.get(target);
        if (state == null || executor == null) {
            runtime.setStatus("MCP: unknown server '" + target + "'");
            return;
        }
        var configs = configLoader.apply(runtime.cwd());
        var cfg = configs.stream().filter(c -> c.name().equals(target)).findFirst().orElse(null);
        if (cfg == null) {
            runtime.setStatus("MCP: '" + target + "' is no longer in the config");
            return;
        }
        runtime.setStatus("MCP: reconnecting " + target + "…");
        var registry = runtime.tools();
        executor.execute(() -> {
            McpClient old = state.client;
            if (old != null)
                old.close();
            state.client = null;
            state.connected = false;
            state.toolNames.clear();
            connect(cfg, state, registry);
            runtime.setStatus("MCP: " + target + (state.connected ? " connected" : " failed"));
        });
    }

    private List<String> statusLines() {
        if (servers.isEmpty())
            return List.of();
        var lines = new ArrayList<String>();
        for (var state : servers.values()) {
            if (state.connected)
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
        final List<String> toolNames = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile McpClient client;
        volatile boolean connected;
        volatile String error;

        ServerState(String name) {
            this.name = name;
        }
    }
}
