package org.pijota.extensions.plan;

import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeExtensionRuntime;
import org.pijota.tools.Tool;
import org.pijota.tools.ToolRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanExtensionTest {

    @Test
    void nameIsPlan() {
        var ext = new PlanExtension();
        assertEquals("plan", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new PlanExtension();
        assertNotNull(ext.description());
        assertFalse(ext.description().isBlank());
    }

    /**
     * Leaving plan mode gives back the tools it borrowed, not a guess at what "normal" means.
     *
     * <p>
     * It used to restore a hardcoded seven, so a session that began with more came back with fewer and stayed that way:
     * webfetch, websearch, question, todo, the jobs and task tools and skill were gone for the rest of it, with nothing
     * said. Reproduced live as a model that asked for its tools mid-session and was told seven, in a session whose
     * bootstrap had twenty.
     */
    @Test
    void leavingPlanModeGivesBackEveryToolItBorrowed() throws Exception {
        var registry = registryWith("read", "bash", "edit", "write", "grep", "glob", "ls", "webfetch", "websearch",
                                        "question", "todo", "skill");
        var runtime = new FakeExtensionRuntime();
        runtime.setToolRegistry(registry);
        var api = new FakeExtensionAPI(runtime);
        new PlanExtension().init(api);
        var before = names(registry);

        api.slashCommand("plan").handler().accept(""); // on
        assertFalse(names(registry).contains("write"), "plan mode is read-only: " + names(registry));

        api.slashCommand("plan").handler().accept(""); // off

        assertEquals(before, names(registry), "everything active before plan mode has to come back");
    }

    /**
     * Entering twice must not make the plan set the thing to go back to. Reachable: a session started with the plan
     * flag fires SessionStart after the extension is already on, and /new fires it again.
     */
    @Test
    void enteringPlanModeTwiceStillGivesBackTheOriginalTools() throws Exception {
        var registry = registryWith("read", "bash", "edit", "write", "grep", "glob", "ls", "webfetch");
        var runtime = new FakeExtensionRuntime();
        runtime.setToolRegistry(registry);
        var api = new FakeExtensionAPI(runtime);
        new PlanExtension().init(api);
        var before = names(registry);

        api.slashCommand("plan").handler().accept(""); // on
        runtime.setFlag("plan", "true"); // as a session started with --plan has it
        api.fire(new org.pijota.extensions.ExtensionEvent.SessionStart(null)); // SessionStart enters again
        api.slashCommand("plan").handler().accept(""); // off

        assertEquals(before, names(registry), "the second entry must not overwrite what to restore");
    }

    private static Tool stubTool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + " tool";
            }

            @Override
            public java.util.Map<String, Object> schema() {
                return java.util.Map.of();
            }

            @Override
            public java.util.concurrent.CompletableFuture<Tool.ToolResult> execute(String id, Tool.Args args,
                                            String cwd) {
                return java.util.concurrent.CompletableFuture.completedFuture(Tool.ToolResult.success("ok"));
            }
        };
    }

    private static ToolRegistry registryWith(String... names) {
        var registry = new ToolRegistry();
        for (String name : names)
            registry.register(stubTool(name));
        return registry;
    }

    private static List<String> names(ToolRegistry registry) {
        return registry.activeToolInfo().stream().map(Tool.ToolInfo::name).sorted().toList();
    }
}
