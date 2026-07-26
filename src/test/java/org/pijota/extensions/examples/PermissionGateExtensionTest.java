package org.pijota.extensions.examples;

import org.pijota.extensions.ExtensionResult;
import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeUiContext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PermissionGateExtensionTest {

    private static Object gate(FakeExtensionAPI api, String tool, String command) {
        new PermissionGateExtension().init(api);
        return api.fireToolCall(tool, command);
    }

    @Test
    void isDangerousRmRf() {
        assertTrue(PermissionGateExtension.isDangerous("rm -rf /tmp/test"));
    }

    @Test
    void isDangerousRmRecursive() {
        assertTrue(PermissionGateExtension.isDangerous("rm --recursive dir"));
    }

    @Test
    void isDangerousSudo() {
        assertTrue(PermissionGateExtension.isDangerous("sudo systemctl restart"));
    }

    @Test
    void isDangerousChmod777() {
        assertTrue(PermissionGateExtension.isDangerous("chmod 777 script.sh"));
    }

    @Test
    void isDangerousChown777() {
        assertTrue(PermissionGateExtension.isDangerous("chown root:root 777"));
    }

    @Test
    void notDangerousSimpleRm() {
        assertFalse(PermissionGateExtension.isDangerous("rm file.txt"));
    }

    @Test
    void notDangerousEcho() {
        assertFalse(PermissionGateExtension.isDangerous("echo hello"));
    }

    @Test
    void notDangerousLs() {
        assertFalse(PermissionGateExtension.isDangerous("ls -la"));
    }

    @Test
    void parseCommandExtractsCommand() {
        assertEquals("ls -la", PermissionGateExtension.parseCommand("{\"command\":\"ls -la\"}"));
    }

    @Test
    void parseCommandInvalidJson() {
        assertNull(PermissionGateExtension.parseCommand("not json"));
    }

    @Test
    void parseCommandNullArgs() {
        assertNull(PermissionGateExtension.parseCommand("{\"other\":\"value\"}"));
    }

    // ---- guard (onToolCall) ----

    @Test
    void blocksDangerousBashWhenNoUi() {
        assertInstanceOf(ExtensionResult.Block.class, gate(new FakeExtensionAPI(), "bash", "rm -rf /"));
    }

    @Test
    void blocksDangerousTmuxBashWhenNoUi() {
        // tmux_bash runs shell commands too, so it must be gated like bash
        assertInstanceOf(ExtensionResult.Block.class, gate(new FakeExtensionAPI(), "tmux_bash", "sudo reboot"));
    }

    @Test
    void ignoresNonShellTools() {
        assertNull(gate(new FakeExtensionAPI(), "read", "rm -rf /"));
    }

    @Test
    void allowsSafeCommand() {
        assertNull(gate(new FakeExtensionAPI(), "bash", "ls -la"));
    }

    @Test
    void allowsDangerousCommandWhenUserConfirms() {
        var api = new FakeExtensionAPI();
        var ui = new FakeUiContext();
        ui.setConfirmResult(true);
        api.setUi(ui);
        assertNull(gate(api, "bash", "rm -rf /"));
    }

    @Test
    void blocksDangerousCommandWhenUserDeclines() {
        var api = new FakeExtensionAPI();
        var ui = new FakeUiContext();
        ui.setConfirmResult(false);
        api.setUi(ui);
        assertInstanceOf(ExtensionResult.Block.class, gate(api, "bash", "rm -rf /"));
    }
}
