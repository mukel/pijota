package ai.pijota.extensions.examples;

import ai.pijota.extensions.testkit.FakeExtensionAPI;
import ai.pijota.extensions.testkit.FakeExtensionRuntime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TodoExtensionTest {

    @Test
    void todowriteIsOffByDefault() {
        var api = new FakeExtensionAPI(new FakeExtensionRuntime());
        new TodoExtension().init(api);
        assertTrue(api.tools().isEmpty(), "todowrite must not be registered without --todo");
    }

    @Test
    void todowriteRegisteredWhenFlagEnabled() {
        var runtime = new FakeExtensionRuntime();
        runtime.setFlag("todo", "true");
        var api = new FakeExtensionAPI(runtime);
        new TodoExtension().init(api);
        assertNotNull(api.tool("todowrite"), "todowrite must be registered with --todo");
    }
}
