package ai.pijota.extensions.examples;

import ai.pijota.extensions.ExtensionEvent;
import ai.pijota.extensions.ExtensionResult;
import ai.pijota.extensions.testkit.FakeExtensionAPI;
import ai.pijota.extensions.testkit.FakeExtensionRuntime;
import ai.pijota.extensions.testkit.FakeUiContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecurityExtensionTest {

    @Test
    void summarizeShortCommandReturnsSame() {
        var cmd = "ls -la";
        assertEquals("ls -la", SecurityExtension.summarize(cmd));
    }

    @Test
    void summarizeLongCommandTruncates() {
        var cmd = "x".repeat(200);
        var result = SecurityExtension.summarize(cmd);
        assertTrue(result.endsWith("..."));
        assertTrue(result.length() <= 120);
    }

    @Test
    void summarizeExactly120Chars() {
        var cmd = "x".repeat(120);
        assertEquals(cmd, SecurityExtension.summarize(cmd));
    }

    @Test
    void nameIsSecurity() {
        var ext = new SecurityExtension();
        assertEquals("security", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new SecurityExtension();
        assertNotNull(ext.description());
        assertFalse(ext.description().isBlank());
    }

    @Test
    void initRegistersSlashCommandAndHandlers() {
        var api = new FakeExtensionAPI();
        var ext = new SecurityExtension();
        ext.init(api);
        assertNotNull(api.slashCommand("security"));
        assertEquals(1, api.listeners(ExtensionEvent.ToolCall.class).size());
        assertEquals(1, api.listeners(ExtensionEvent.UserBash.class).size());
    }

    @Test
    void toolCallBlocksDangerousBashCommand() {
        var api = new FakeExtensionAPI();
        var ext = new SecurityExtension();
        ext.init(api);
        var result = api.fire(new ExtensionEvent.ToolCall("id", "bash", "{\"command\":\"rm -rf /\"}", true));
        assertInstanceOf(ExtensionResult.Block.class, result);
        assertTrue(((ExtensionResult.Block) result).reason().contains("recursive delete"));
    }

    @Test
    void toolCallBlocksDangerousTmuxBashCommand() {
        // tmux_bash runs shell commands too, so the same guard must cover it (not just the bash tool)
        var api = new FakeExtensionAPI();
        var ext = new SecurityExtension();
        ext.init(api);
        var result = api.fireToolCall("tmux_bash", "rm -rf /");
        assertInstanceOf(ExtensionResult.Block.class, result);
        assertTrue(((ExtensionResult.Block) result).reason().contains("recursive delete"));
    }

    @Test
    void toolCallAllowsSafeBashCommand() {
        var api = new FakeExtensionAPI();
        var ext = new SecurityExtension();
        ext.init(api);
        var result = api.fire(new ExtensionEvent.ToolCall("id", "bash", "{\"command\":\"ls -la\"}", true));
        assertNull(result);
    }

    @Test
    void toolCallIgnoresNonBashTools() {
        var api = new FakeExtensionAPI();
        var ext = new SecurityExtension();
        ext.init(api);
        var result = api.fire(new ExtensionEvent.ToolCall("id", "read", "{}", true));
        assertNull(result);
    }

    @Test
    void onReadyReadsFlagAndDisables() {
        var runtime = new FakeExtensionRuntime();
        runtime.setFlag("security", "false");
        var ext = new SecurityExtension();
        ext.onReady(runtime);
        // toggle slash command should re-enable and set status
        var api = new FakeExtensionAPI(runtime);
        ext.init(api);
        api.slashCommand("security").handler().accept("");
        assertTrue(runtime.keyedStatuses().containsKey("security"));
    }

    @Test
    void userBashBlocksDangerousCommandWithoutUi() {
        var api = new FakeExtensionAPI();
        var ext = new SecurityExtension();
        ext.init(api);
        var result = (ExtensionResult.UserBashResult) api.fire(new ExtensionEvent.UserBash("rm -rf /", false, null));
        assertTrue(result.blocked());
    }

    @Test
    void userBashAllowsSafeCommand() {
        var api = new FakeExtensionAPI();
        var ext = new SecurityExtension();
        ext.init(api);
        var result = (ExtensionResult.UserBashResult) api.fire(new ExtensionEvent.UserBash("ls -la", false, null));
        assertFalse(result.blocked());
    }

    @Test
    void userBashConfirmsWithUiWhenDangerous() {
        var runtime = new FakeExtensionRuntime();
        var api = new FakeExtensionAPI(runtime);
        var ui = new FakeUiContext();
        ui.setConfirmResult(true);
        api.setUi(ui);
        var ext = new SecurityExtension();
        ext.init(api);
        var result = (ExtensionResult.UserBashResult) api.fire(new ExtensionEvent.UserBash("rm -rf /", false, null));
        assertFalse(result.blocked());
    }

    @Test
    void toggleSlashCommandTogglesEnabledState() {
        var runtime = new FakeExtensionRuntime();
        var api = new FakeExtensionAPI(runtime);
        var ext = new SecurityExtension();
        ext.init(api);
        ext.onReady(runtime);
        api.slashCommand("security").handler().accept("");
        // after toggle, dangerous commands are allowed
        var result = api.fire(new ExtensionEvent.ToolCall("id", "bash", "{\"command\":\"rm -rf /\"}", true));
        assertNull(result);
    }
}
