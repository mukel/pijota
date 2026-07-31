package org.pijota.extensions.mcp;

import org.pijota.testkit.Await;
import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeExtensionRuntime;
import org.pijota.extensions.testkit.FakeUiContext;
import org.pijota.tools.Tool;
import org.pijota.tools.ToolRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpExtensionTest {

    private record Fixture(McpExtension ext, FakeExtensionAPI api, FakeExtensionRuntime runtime, ToolRegistry registry,
                                    List<FakeMcpTransport> transports) {
    }

    private static Fixture fixture(Function<Path, List<McpServerConfig>> loader, FakeMcpTransport.CallHandler calls,
                                    Map<String, Object>... tools) {
        var registry = new ToolRegistry();
        var runtime = new FakeExtensionRuntime();
        runtime.setToolRegistry(registry);
        var ui = new FakeUiContext();
        runtime.setUi(ui);
        var api = new FakeExtensionAPI(runtime);
        api.setUi(ui);

        var created = new ArrayList<FakeMcpTransport>();
        McpTransport.Factory factory = (cfg, cwd) -> {
            var t = new FakeMcpTransport(List.of(List.of(tools)), calls);
            created.add(t);
            return t;
        };
        var ext = new McpExtension(loader, factory);
        ext.init(api);
        return new Fixture(ext, api, runtime, registry, created);
    }

    private static Function<Path, List<McpServerConfig>> oneServer() {
        return cwd -> List.of(new McpServerConfig("srv", "cmd", List.of(), Map.of(), true));
    }

    @Test
    void registersNamespacedToolsFromTheServer() {
        var f = fixture(oneServer(), (t, a) -> FakeMcpTransport.Outcome.result("ok"),
                                        FakeMcpTransport.tool("echo", "Echo"));
        f.ext.onReady(f.runtime);

        assertTrue(f.registry.get("mcp__srv__echo").isPresent(), "MCP tool should be registered and namespaced");
        f.ext.onShutdown(f.runtime);
    }

    @Test
    void registeredToolExecutesThroughTheClient() throws Exception {
        var f = fixture(oneServer(), (t, a) -> FakeMcpTransport.Outcome.result("pong:" + a.path("v").asText("")),
                                        FakeMcpTransport.tool("echo", "Echo"));
        f.ext.onReady(f.runtime);

        Tool tool = f.registry.get("mcp__srv__echo").orElseThrow();
        Tool.ToolResult result = tool.execute("id-1", new Tool.Args(Map.of("v", "hi")), null).get(3, TimeUnit.SECONDS);
        assertFalse(result.isError());
        assertEquals("pong:hi", result.content());
        f.ext.onShutdown(f.runtime);
    }

    @Test
    void noConfigRegistersNoToolsAndShowsNothing() {
        var f = fixture(cwd -> List.of(), (t, a) -> FakeMcpTransport.Outcome.result("ok"));
        f.ext.onReady(f.runtime);

        assertTrue(f.registry.availableToolInfo().isEmpty(), "no servers => no tools");
        assertTrue(f.api.statusLineSuppliers().getFirst().get().isEmpty(), "no status lines without servers");
        // /mcp with no servers configured just reports that
        f.api.slashCommand("mcp").handler().accept("");
        assertTrue(f.runtime.statusCalls().stream().anyMatch(s -> s.contains("No MCP servers")),
                                        f.runtime.statusCalls().toString());
    }

    @Test
    void mcpCommandListsServersAndStatusLineReflectsConnection() {
        var f = fixture(oneServer(), (t, a) -> FakeMcpTransport.Outcome.result("ok"),
                                        FakeMcpTransport.tool("echo", "Echo"));
        f.ext.onReady(f.runtime);

        f.api.slashCommand("mcp").handler().accept("");
        assertTrue(f.api.lastSentMessage().contains("srv"), f.api.lastSentMessage());

        var status = f.api.statusLineSuppliers().getFirst().get();
        assertTrue(status.stream().anyMatch(l -> l.contains("mcp:srv")), status.toString());
        f.ext.onShutdown(f.runtime);
    }

    @Test
    void shutdownClosesEveryServerTransport() {
        var f = fixture(oneServer(), (t, a) -> FakeMcpTransport.Outcome.result("ok"),
                                        FakeMcpTransport.tool("echo", "Echo"));
        f.ext.onReady(f.runtime);
        assertEquals(1, f.transports.size());

        f.ext.onShutdown(f.runtime);
        assertTrue(f.transports.getFirst().isClosed(), "server transport should be closed on shutdown");
    }

    @Test
    void multipleServersRegisterDistinctlyNamespacedTools() {
        Function<Path, List<McpServerConfig>> loader = cwd -> List.of(
                                        new McpServerConfig("a", "cmd", List.of(), Map.of(), true),
                                        new McpServerConfig("b", "cmd", List.of(), Map.of(), true));
        var f = fixture(loader, (t, a) -> FakeMcpTransport.Outcome.result("ok"), FakeMcpTransport.tool("echo", "Echo"));
        f.ext.onReady(f.runtime);

        assertTrue(f.registry.get("mcp__a__echo").isPresent());
        assertTrue(f.registry.get("mcp__b__echo").isPresent(), "same tool name from two servers must not collide");
        var status = f.api.statusLineSuppliers().getFirst().get();
        assertTrue(status.stream().anyMatch(l -> l.contains("mcp:a"))
                                        && status.stream().anyMatch(l -> l.contains("mcp:b")), status.toString());
        f.ext.onShutdown(f.runtime);
    }

    @Test
    void aFailedServerDoesNotBlockTheOthers() {
        var registry = new ToolRegistry();
        var runtime = new FakeExtensionRuntime();
        runtime.setToolRegistry(registry);
        var api = new FakeExtensionAPI(runtime);

        McpTransport.Factory factory = (cfg, cwd) -> {
            if (cfg.name().equals("bad"))
                throw new IOException("spawn failed");
            return new FakeMcpTransport(List.of(List.of(FakeMcpTransport.tool("echo", "Echo"))),
                                            (t, a) -> FakeMcpTransport.Outcome.result("ok"));
        };
        Function<Path, List<McpServerConfig>> loader = cwd -> List.of(
                                        new McpServerConfig("bad", "cmd", List.of(), Map.of(), true),
                                        new McpServerConfig("good", "cmd", List.of(), Map.of(), true));
        var ext = new McpExtension(loader, factory);
        ext.init(api);
        ext.onReady(runtime);

        assertTrue(registry.get("mcp__good__echo").isPresent(), "healthy server still registers");
        assertFalse(registry.get("mcp__bad__echo").isPresent(), "failed server registers nothing");
        api.slashCommand("mcp").handler().accept("");
        assertTrue(api.lastSentMessage().contains("bad") && api.lastSentMessage().contains("offline"),
                                        api.lastSentMessage());
        ext.onShutdown(runtime);
    }

    @Test
    void aFailingServersStderrTailIsShownInTheOfflineReason() {
        var runtime = new FakeExtensionRuntime();
        runtime.setToolRegistry(new ToolRegistry());
        var api = new FakeExtensionAPI(runtime);

        // A transport that opens but whose server dies talking, having explained itself on stderr (the npx-404 shape).
        McpTransport.Factory factory = (cfg, cwd) -> new McpTransport() {
            public void send(String line) {}

            public String receive() throws IOException {
                throw new IOException("server closed the stream");
            }

            public void close() {}

            public String stderrTail() {
                return "npm error 404 Not Found";
            }
        };
        var ext = new McpExtension(oneServer(), factory);
        ext.init(api);
        ext.onReady(runtime);

        api.slashCommand("mcp").handler().accept("");
        assertTrue(api.lastSentMessage().contains("npm error 404"),
                                        "the offline reason must carry the server's stderr tail: "
                                                                        + api.lastSentMessage());
        ext.onShutdown(runtime);
    }

    @Test
    void disabledServersAreListedAsDisabledNotMissing() {
        var registry = new ToolRegistry();
        var runtime = new FakeExtensionRuntime();
        runtime.setToolRegistry(registry);
        var api = new FakeExtensionAPI(runtime);

        Function<Path, List<McpServerConfig>> loader = cwd -> List.of(
                                        new McpServerConfig("dormant", "cmd", List.of(), Map.of(), false));
        var ext = new McpExtension(loader, (cfg, cwd) -> {
            throw new AssertionError("a disabled server must never be auto-started");
        });
        ext.init(api);
        ext.onReady(runtime);

        api.slashCommand("mcp").handler().accept("");
        assertTrue(api.lastSentMessage().contains("dormant") && api.lastSentMessage().contains("disabled"),
                                        "configured-but-disabled shows as disabled, not as missing: "
                                                                        + api.lastSentMessage());
        assertTrue(api.statusLineSuppliers().getFirst().get().isEmpty(),
                                        "a deliberately-disabled server is not footer noise");
        ext.onShutdown(runtime);
    }

    @Test
    void aFailedHandshakeClosesTheTransportInsteadOfLeakingIt() {
        var registry = new ToolRegistry();
        var runtime = new FakeExtensionRuntime();
        runtime.setToolRegistry(registry);
        var api = new FakeExtensionAPI(runtime);

        var created = new ArrayList<FakeMcpTransport>();
        McpTransport.Factory factory = (cfg, cwd) -> {
            var t = new FakeMcpTransport(List.of(List.of(FakeMcpTransport.tool("echo", "Echo"))),
                                            (x, a) -> FakeMcpTransport.Outcome.result("ok")).failHandshake();
            created.add(t);
            return t;
        };
        var ext = new McpExtension(oneServer(), factory);
        ext.init(api);
        ext.onReady(runtime);

        assertTrue(created.getFirst().isClosed(), "transport must be closed when the handshake fails (no leak)");
        assertFalse(registry.get("mcp__srv__echo").isPresent());
        ext.onShutdown(runtime);
    }

    @Test
    void mcpToolsAreAvailableButNotActiveWhenTheActiveSetWasRestricted() {
        var f = fixture(oneServer(), (t, a) -> FakeMcpTransport.Outcome.result("ok"),
                                        FakeMcpTransport.tool("echo", "Echo"));
        f.registry.setActiveTools(Set.of("some-other-tool")); // a restricted active set predates MCP registration
        f.ext.onReady(f.runtime);

        assertTrue(f.registry.availableToolInfo().stream().anyMatch(t -> t.name().equals("mcp__srv__echo")),
                                        "a freshly registered MCP tool remains available when policy allows it");
        assertFalse(f.registry.activeToolNames().contains("mcp__srv__echo"),
                                        "an explicit active set is not widened by late registration");
        f.ext.onShutdown(f.runtime);
    }

    @Test
    void reconnectUnknownServerReportsError() throws Exception {
        var f = fixture(oneServer(), (t, a) -> FakeMcpTransport.Outcome.result("ok"),
                                        FakeMcpTransport.tool("echo", "Echo"));
        f.ext.onReady(f.runtime);

        f.api.slashCommand("mcp").handler().accept("reconnect nope");
        Await.until(() -> f.runtime.statusCalls().stream().anyMatch(s -> s.contains("unknown server")));
        f.ext.onShutdown(f.runtime);
    }

    @Test
    void reconnectClosesTheOldTransportAndReopens() throws Exception {
        var f = fixture(oneServer(), (t, a) -> FakeMcpTransport.Outcome.result("ok"),
                                        FakeMcpTransport.tool("echo", "Echo"));
        f.ext.onReady(f.runtime);
        assertEquals(1, f.transports.size());

        f.api.slashCommand("mcp").handler().accept("reconnect srv");

        // wait for the reconnect to fully settle: a new transport opened AND the tool re-registered
        Await.until(() -> f.transports.size() == 2 && f.registry.get("mcp__srv__echo").isPresent());
        assertTrue(f.transports.getFirst().isClosed(), "old transport closed on reconnect");
        f.ext.onShutdown(f.runtime);
    }

    @Test
    void aServerThatDiesMidSessionReadsAsOffline() {
        var f = fixture(oneServer(), (t, a) -> FakeMcpTransport.Outcome.result("ok"),
                                        FakeMcpTransport.tool("echo", "Echo"));
        f.ext.onReady(f.runtime);
        assertTrue(f.api.statusLineSuppliers().getFirst().get().stream().anyMatch(l -> l.contains("✓")),
                                        "connected server shows ✓");

        f.transports.getFirst().close(); // simulate the server process dying

        assertTrue(f.api.statusLineSuppliers().getFirst().get().stream().anyMatch(l -> l.contains("✗ offline")),
                                        "a dead server must report offline, not a stale ✓");
        f.ext.onShutdown(f.runtime);
    }

    @Test
    void disconnectKillsTheServerAndRemovesItsTools() throws Exception {
        var f = fixture(oneServer(), (t, a) -> FakeMcpTransport.Outcome.result("ok"),
                                        FakeMcpTransport.tool("echo", "Echo"));
        f.ext.onReady(f.runtime);
        assertTrue(f.registry.get("mcp__srv__echo").isPresent());

        f.api.slashCommand("mcp").handler().accept("disconnect srv");
        Await.until(() -> f.registry.get("mcp__srv__echo").isEmpty());
        assertTrue(f.transports.getFirst().isClosed(), "server process closed on disconnect");
        f.ext.onShutdown(f.runtime);
    }

    @Test
    void connectBringsBackADisconnectedServer() throws Exception {
        var f = fixture(oneServer(), (t, a) -> FakeMcpTransport.Outcome.result("ok"),
                                        FakeMcpTransport.tool("echo", "Echo"));
        f.ext.onReady(f.runtime);
        f.api.slashCommand("mcp").handler().accept("disconnect srv");
        Await.until(() -> f.registry.get("mcp__srv__echo").isEmpty());

        f.api.slashCommand("mcp").handler().accept("connect srv");
        Await.until(() -> f.registry.get("mcp__srv__echo").isPresent());
        assertEquals(2, f.transports.size(), "connect opens a fresh transport");
        f.ext.onShutdown(f.runtime);
    }

    @Test
    void reloadAddsRemovesAndRestartsServers() throws Exception {
        var registry = new ToolRegistry();
        var runtime = new FakeExtensionRuntime();
        runtime.setToolRegistry(registry);
        var api = new FakeExtensionAPI(runtime);

        // mutable config source the reload re-reads
        var config = new AtomicReference<List<McpServerConfig>>(
                                        List.of(server("a", List.of()), server("b", List.of())));
        McpTransport.Factory factory = (cfg, cwd) -> new FakeMcpTransport(
                                        List.of(List.of(FakeMcpTransport.tool("echo", "Echo"))),
                                        (t, a) -> FakeMcpTransport.Outcome.result("ok"));
        var ext = new McpExtension(cwd -> config.get(), factory);
        ext.init(api);
        ext.onReady(runtime);
        Await.until(() -> registry.get("mcp__a__echo").isPresent() && registry.get("mcp__b__echo").isPresent());

        // drop b, keep a unchanged, change a's args (→ restart), add c
        config.set(List.of(server("a", List.of("--v2")), server("c", List.of())));
        api.slashCommand("mcp").handler().accept("reload");

        Await.until(() -> registry.get("mcp__c__echo").isPresent() // added
                                        && registry.get("mcp__b__echo").isEmpty() // removed
                                        && registry.get("mcp__a__echo").isPresent()); // restarted (changed)
        // The "+1 -1 ~1" status is emitted at the end of the async reload, after the registry is reconciled,
        // so await it rather than asserting immediately (the registry Await above can win the race).
        Await.until(() -> runtime.statusCalls().stream().anyMatch(s -> s.contains("+1 -1 ~1")));
        ext.onShutdown(runtime);
    }

    private static McpServerConfig server(String name, List<String> args) {
        return new McpServerConfig(name, "cmd", args, Map.of(), true);
    }

}
