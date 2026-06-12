package ai.pijota.extensions.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PermissionGateExtensionTest {

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
}
