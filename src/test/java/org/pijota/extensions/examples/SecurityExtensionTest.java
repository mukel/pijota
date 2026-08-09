package org.pijota.extensions.examples;

import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.ExtensionResult;
import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeExtensionRuntime;
import org.pijota.extensions.testkit.FakeUiContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

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
    void toolCallBlocksDangerousJobsRunCommand() {
        // jobs_run spawns shell commands in the background; it must be gated by the same guard, not a bypass
        var api = new FakeExtensionAPI();
        var ext = new SecurityExtension();
        ext.init(api);
        var result = api.fireToolCall("jobs_run", "rm -rf /");
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
    void onReadyReadsFlagAndToggleReflectsInStatusLine() {
        var runtime = new FakeExtensionRuntime();
        runtime.setFlag("security", "false");
        var ext = new SecurityExtension();
        var api = new FakeExtensionAPI(runtime);
        ext.init(api);
        ext.onReady(runtime);
        var status = api.statusLineSuppliers().getFirst();
        assertTrue(String.join("", status.get()).contains("unlocked"), "flag=false → blocking off → unlocked");

        api.slashCommand("security").handler().accept(""); // toggle → blocking on

        // Blocking is the default, so being on earns no chip: the bar names the exception. What the toggle owes the
        // user is a word about what it just did, since its only other effect is a chip disappearing.
        assertEquals(List.of(), status.get(), "the guarded state is the quiet one");
        assertTrue(runtime.statusCalls().stream().anyMatch(m -> m.contains("blocking dangerous")),
                                        "the toggle must say what it did: " + runtime.statusCalls());
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

    /**
     * A veto leaves the machine as it was, so nothing else records that it happened; an override changes the machine
     * and equally leaves no trace of who decided. The log is the only place either fact survives the session.
     */
    @Test
    void everyVerdictOnADangerousCommandIsWrittenToTheLog() {
        var logger = (Logger) LoggerFactory.getLogger(SecurityExtension.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        Level saved = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            var api = new FakeExtensionAPI();
            var ext = new SecurityExtension();
            ext.init(api);
            api.fireToolCall("bash", "sudo rm -rf /");

            var lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(lines.stream().anyMatch(l -> l.contains("blocked") && l.contains("sudo rm -rf /")),
                                            "the blocked call must be on the record: " + lines);
        } finally {
            logger.setLevel(saved);
            logger.detachAppender(appender);
        }
    }
}
