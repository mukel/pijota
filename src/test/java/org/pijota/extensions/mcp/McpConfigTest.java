package org.pijota.extensions.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigTest {

    private static Path write(Path dir, String name, String json) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, json);
        return f;
    }

    @Test
    void absentFilesYieldNoServers(@TempDir Path dir) {
        var servers = McpConfig.load(dir.resolve("nope.json"), dir.resolve("also-nope.json"), k -> null);
        assertTrue(servers.isEmpty());
    }

    @Test
    void parsesServersAndExpandsEnvPlaceholders(@TempDir Path dir) throws Exception {
        Path global = write(dir, "global.json", """
                                        { "servers": {
                                          "fs": { "command": "npx", "args": ["-y","server-filesystem","${ROOT}"] },
                                          "gh": { "command": "gh-mcp", "env": { "TOKEN": "${GH_TOKEN}" } }
                                        }}""");

        var servers = McpConfig.load(global, dir.resolve("none.json"),
                                        Map.of("ROOT", "/tmp", "GH_TOKEN", "secret")::get);

        var fs = servers.stream().filter(s -> s.name().equals("fs")).findFirst().orElseThrow();
        assertEquals("npx", fs.command());
        assertEquals(List.of("-y", "server-filesystem", "/tmp"), fs.args());

        var gh = servers.stream().filter(s -> s.name().equals("gh")).findFirst().orElseThrow();
        assertEquals("secret", gh.env().get("TOKEN"));
    }

    @Test
    void missingEnvPlaceholderExpandsToEmpty(@TempDir Path dir) throws Exception {
        Path global = write(dir, "g.json", """
                                        { "servers": { "x": { "command": "run", "args": ["${UNSET}"] } } }""");
        var servers = McpConfig.load(global, null, k -> null);
        assertEquals(List.of(""), servers.getFirst().args());
    }

    @Test
    void disabledServersAreKeptWithTheirFlagAndProjectOverridesGlobal(@TempDir Path dir) throws Exception {
        Path global = write(dir, "g.json", """
                                        { "servers": {
                                          "off": { "command": "x", "enabled": false },
                                          "shared": { "command": "global-cmd" }
                                        }}""");
        Path project = write(dir, "p.json", """
                                        { "servers": { "shared": { "command": "project-cmd" } } }""");

        var servers = McpConfig.load(global, project, k -> null);
        // Disabled servers stay in the list (so the UI can say "disabled" rather than "none configured" and
        // /mcp connect can start them on request); the flag records the intent.
        var off = servers.stream().filter(s -> s.name().equals("off")).findFirst().orElseThrow();
        assertFalse(off.enabled(), "disabled server is kept, flagged disabled");
        var shared = servers.stream().filter(s -> s.name().equals("shared")).findFirst().orElseThrow();
        assertTrue(shared.enabled(), "enabled is the default");
        assertEquals("project-cmd", shared.command(), "project config overrides global");
    }

    @Test
    void malformedJsonIsIgnoredNotThrown(@TempDir Path dir) throws Exception {
        Path bad = write(dir, "bad.json", "{ this is not valid json ");
        assertDoesNotThrow(() -> McpConfig.load(bad, null, k -> null));
        assertTrue(McpConfig.load(bad, null, k -> null).isEmpty());
    }

    @Test
    void serversWithBlankOrMissingCommandAreSkipped(@TempDir Path dir) throws Exception {
        Path global = write(dir, "g.json", """
                                        { "servers": {
                                          "nocmd": { "args": ["x"] },
                                          "blank": { "command": "  " },
                                          "good":  { "command": "run" }
                                        }}""");
        var servers = McpConfig.load(global, null, k -> null);
        assertEquals(List.of("good"), servers.stream().map(McpServerConfig::name).toList());
    }

    @Test
    void enabledTrueIsKeptAndCommandAndMultipleVarsExpand(@TempDir Path dir) throws Exception {
        Path global = write(dir, "g.json", """
                                        { "servers": {
                                          "x": { "command": "${BIN}", "args": ["${A}-${B}"], "enabled": true }
                                        }}""");
        var servers = McpConfig.load(global, null, Map.of("BIN", "mybin", "A", "1", "B", "2")::get);
        var x = servers.getFirst();
        assertEquals("mybin", x.command());
        assertEquals(List.of("1-2"), x.args());
    }

    @Test
    void literalTextWithoutPlaceholdersIsPreserved(@TempDir Path dir) throws Exception {
        Path global = write(dir, "g.json", """
                                        { "servers": { "x": { "command": "echo", "args": ["a $b c"] } } }""");
        var servers = McpConfig.load(global, null, k -> "SHOULD_NOT_BE_USED");
        assertEquals(List.of("a $b c"), servers.getFirst().args(), "a bare $ without ${} is left untouched");
    }
}
