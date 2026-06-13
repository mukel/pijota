package ai.pijota.extensions.mcp;

import ai.pijota.extensions.testkit.FakeExtensionAPI;
import ai.pijota.extensions.testkit.FakeExtensionRuntime;
import ai.pijota.extensions.testkit.FakeUiContext;
import ai.pijota.tools.Tool;
import ai.pijota.tools.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
        Tool.ToolResult result = tool.execute("id-1", Map.of("v", "hi"), null).get(3, TimeUnit.SECONDS);
        assertFalse(result.isError());
        assertEquals("pong:hi", result.content());
        f.ext.onShutdown(f.runtime);
    }

    @Test
    void noConfigRegistersNoToolsAndShowsNothing() {
        var f = fixture(cwd -> List.of(), (t, a) -> FakeMcpTransport.Outcome.result("ok"));
        f.ext.onReady(f.runtime);

        assertTrue(f.registry.allTools().isEmpty(), "no servers => no tools");
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
                throw new java.io.IOException("spawn failed");
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
    void mcpToolsAreEnabledEvenWhenTheActiveSetWasRestricted() {
        var f = fixture(oneServer(), (t, a) -> FakeMcpTransport.Outcome.result("ok"),
                                        FakeMcpTransport.tool("echo", "Echo"));
        f.registry.enableOnly(List.of("some-other-tool")); // a restricted active set predates MCP registration
        f.ext.onReady(f.runtime);

        assertTrue(f.registry.enabledToolNames().contains("mcp__srv__echo"),
                                        "a freshly registered MCP tool must be enabled, not hidden by enableOnly");
        f.ext.onShutdown(f.runtime);
    }

    @Test
    void reconnectUnknownServerReportsError() {
        var f = fixture(oneServer(), (t, a) -> FakeMcpTransport.Outcome.result("ok"),
                                        FakeMcpTransport.tool("echo", "Echo"));
        f.ext.onReady(f.runtime);

        f.api.slashCommand("mcp").handler().accept("reconnect nope");
        assertTrue(f.runtime.statusCalls().stream().anyMatch(s -> s.contains("unknown server")),
                                        f.runtime.statusCalls().toString());
        f.ext.onShutdown(f.runtime);
    }

    @Test
    void reconnectClosesTheOldTransportAndReopens() throws Exception {
        var f = fixture(oneServer(), (t, a) -> FakeMcpTransport.Outcome.result("ok"),
                                        FakeMcpTransport.tool("echo", "Echo"));
        f.ext.onReady(f.runtime);
        assertEquals(1, f.transports.size());

        f.api.slashCommand("mcp").handler().accept("reconnect srv");

        await(() -> f.transports.size() == 2); // a new transport was opened by the async reconnect
        assertTrue(f.transports.getFirst().isClosed(), "old transport closed on reconnect");
        assertTrue(f.registry.get("mcp__srv__echo").isPresent(), "tool still present after reconnect");
        f.ext.onShutdown(f.runtime);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean())
                return;
            Thread.sleep(10);
        }
        fail("condition not met within timeout");
    }
}
