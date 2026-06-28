package ai.pijota.extensions.background;

import ai.pijota.extensions.testkit.FakeExtensionAPI;
import ai.pijota.extensions.testkit.FakeExtensionRuntime;
import ai.pijota.tools.Tool;
import ai.pijota.util.Executables;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BackgroundJobsExtensionTest {
    @TempDir
    Path tempDir;

    private FakeExtensionAPI initWithFlag(boolean on) {
        var runtime = new FakeExtensionRuntime();
        if (on)
            runtime.setFlag("background", "true");
        var api = new FakeExtensionAPI(runtime);
        new BackgroundJobsExtension().init(api);
        return api;
    }

    private Tool.ToolResult run(FakeExtensionAPI api, String tool, Map<String, Object> args) throws Exception {
        return api.tool(tool).execute().execute("id", new Tool.Args(args), tempDir.toString()).get();
    }

    @Test
    void noToolsWithoutTheFlag() {
        var api = initWithFlag(false);
        assertTrue(api.tools().isEmpty(), "background tools must be opt-in (--background)");
    }

    @Test
    void registersTheJobToolsAndPanelWithTheFlag() {
        var api = initWithFlag(true);
        assertNotNull(api.tool("bash_background"));
        assertNotNull(api.tool("bash_output"));
        assertNotNull(api.tool("bash_kill"));
        assertFalse(api.statusLineSuppliers().isEmpty());
    }

    @Test
    void toolsValidateTheirArguments() throws Exception {
        var api = initWithFlag(true);
        assertTrue(run(api, "bash_background", Map.of()).isError(), "missing command");
        assertTrue(run(api, "bash_output", Map.of("id", "bg_999")).content().contains("No background job"));
        assertTrue(run(api, "bash_kill", Map.of("id", "bg_999")).isError());
    }

    @Test
    void runsACommandInTheBackgroundAndReadsItsOutput() throws Exception {
        assumeTrue(Executables.isAvailable("sh"), "needs a POSIX shell");
        var api = initWithFlag(true);

        var started = run(api, "bash_background", Map.of("command", "echo hi"));
        assertFalse(started.isError(), started.content());
        String id = (String) started.details().get("jobId");
        assertNotNull(id);

        var out = new StringBuilder();
        long deadline = System.currentTimeMillis() + 5000;
        while (!out.toString().contains("hi") && System.currentTimeMillis() < deadline) {
            out.append(run(api, "bash_output", Map.of("id", id)).content());
            Thread.sleep(20);
        }
        assertTrue(out.toString().contains("hi"), out.toString());
    }
}
