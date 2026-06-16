package ai.pijota.extensions.examples;

import ai.pijota.browser.FakeChrome;
import ai.pijota.extensions.UiContext;
import ai.pijota.extensions.testkit.FakeExtensionAPI;
import ai.pijota.extensions.testkit.FakeExtensionRuntime;
import ai.pijota.tools.Tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebBrowseExtensionTest {

    private FakeExtensionAPI apiWith(FakeExtensionRuntime rt) {
        var api = new FakeExtensionAPI(rt);
        new WebBrowseExtension().init(api);
        return api;
    }

    @Test
    void nameAndDescriptionAreSet() {
        var ext = new WebBrowseExtension();
        assertEquals("browse", ext.name());
        assertTrue(ext.description().contains("--browse"));
    }

    @Test
    void registersNoToolsWhenFlagIsOff() {
        var api = apiWith(new FakeExtensionRuntime()); // no --browse flag
        assertTrue(api.tools().isEmpty(), "browser tools must be opt-in");
    }

    @Test
    void registersBothToolsWithCorrectSchemasWhenFlagIsOn() {
        var rt = new FakeExtensionRuntime();
        rt.setFlag("browse", "true");
        var api = apiWith(rt);

        assertEquals(2, api.tools().size());
        var search = api.tool("web_google_search");
        var visit = api.tool("web_visit_page");
        assertNotNull(search);
        assertNotNull(visit);
        assertEquals("🌐", search.glyph());
        assertEquals("🌐", visit.glyph());
        assertTrue(requiredArgs(search).contains("query"));
        assertTrue(requiredArgs(visit).contains("url"));
    }

    @Test
    void toolsRunAgainstTheBrowserAndReturnMarkdown() throws Exception {
        try (FakeChrome fake = new FakeChrome()) {
            var rt = new FakeExtensionRuntime();
            rt.setFlag("browse", "true");
            rt.setFlag("browse-port", String.valueOf(fake.port())); // attach to the fake → no real launch
            var api = apiWith(rt);

            var visit = api.tool("web_visit_page").toTool().execute("c1", Map.of("url", "https://x"), ".").join();
            assertFalse(visit.isError());
            assertEquals(fake.pageExtract, visit.content());

            var search = api.tool("web_google_search").toTool().execute("c2", Map.of("query", "q"), ".").join();
            assertFalse(search.isError());
            assertTrue(search.content().contains("## Results"));

            new WebBrowseExtension(); // (session is closed via onShutdown in real use)
        }
    }

    @Test
    void hugeOutputIsTruncatedToAFile() throws Exception {
        try (FakeChrome fake = new FakeChrome()) {
            fake.pageExtract = "x".repeat(250_000); // exceeds Util.DEFAULT_MAX_CHARS (100k)
            var rt = new FakeExtensionRuntime();
            rt.setFlag("browse", "true");
            rt.setFlag("browse-port", String.valueOf(fake.port()));
            var api = apiWith(rt);

            var res = api.tool("web_visit_page").toTool().execute("c", Map.of("url", "https://x"), ".").join();
            assertFalse(res.isError());
            assertTrue(res.content().length() < 250_000, "content should be truncated");
            assertFalse(res.details().isEmpty(), "truncation should attach a file reference in details");
        }
    }

    @Test
    void browserErrorsBecomeAnErrorResult() throws Exception {
        try (FakeChrome fake = new FakeChrome()) {
            fake.navigateError = "net::ERR_CONNECTION_REFUSED";
            var rt = new FakeExtensionRuntime();
            rt.setFlag("browse", "true");
            rt.setFlag("browse-port", String.valueOf(fake.port()));
            var api = apiWith(rt);

            var res = api.tool("web_visit_page").toTool().execute("c", Map.of("url", "https://x"), ".").join();
            assertTrue(res.isError());
            assertTrue(res.content().contains("net::ERR_CONNECTION_REFUSED"), res.content());
        }
    }

    @Test
    void decliningTheLaunchPromptReturnsAnErrorNotACrash() {
        var rt = new FakeExtensionRuntime();
        rt.setFlag("browse", "true");
        rt.setFlag("browse-port", "1"); // dead port → would launch; the confirm declines instead
        rt.setUi(declineUi()); // confirm() → false
        var api = apiWith(rt);

        var res = api.tool("web_visit_page").toTool().execute("c", Map.of("url", "https://x"), ".").join();
        assertTrue(res.isError());
        assertTrue(res.content().toLowerCase().contains("declined"), res.content());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requiredArgs(Tool.ToolDefinition def) {
        return (List<Object>) def.schema().get("required");
    }

    /** A UiContext whose confirm() resolves false (the default), to exercise the declined-launch path. */
    private static UiContext declineUi() {
        return new UiContext() {
            @Override
            public void setStatus(String message) {}
        };
    }
}
