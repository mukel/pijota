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
}
