package org.pijota.extensions.examples;

import org.pijota.tools.Tool;
import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeExtensionRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ToolOverrideExtensionTest {

    @Test
    void isBlockedEnvFile() {
        assertTrue(ToolOverrideExtension.isBlocked("/path/to/.env"));
    }

    @Test
    void isBlockedEnvStaging() {
        assertTrue(ToolOverrideExtension.isBlocked(".env.staging"));
    }

    @Test
    void isBlockedSecretsJson() {
        assertTrue(ToolOverrideExtension.isBlocked("/app/secrets.json"));
        assertTrue(ToolOverrideExtension.isBlocked("secret.yaml"));
    }

    @Test
    void isBlockedCredentials() {
        assertTrue(ToolOverrideExtension.isBlocked("credentials.yml"));
    }

    @Test
    void isBlockedSshDir() {
        assertTrue(ToolOverrideExtension.isBlocked("/home/user/.ssh/config"));
    }

    @Test
    void isBlockedAwsDir() {
        assertTrue(ToolOverrideExtension.isBlocked("/home/user/.aws/credentials"));
    }

    @Test
    void isBlockedGnupgDir() {
        assertTrue(ToolOverrideExtension.isBlocked("/home/user/.gnupg/private.key"));
    }

    @Test
    void notBlockedNormalFile() {
        assertFalse(ToolOverrideExtension.isBlocked("src/main.java"));
        assertFalse(ToolOverrideExtension.isBlocked("README.md"));
    }

    @Test
    void notBlockedSimilarButSafe() {
        assertFalse(ToolOverrideExtension.isBlocked("environment.ts"));
        assertFalse(ToolOverrideExtension.isBlocked(".envsample"));
    }

    @Test
    void getIntWithNumber() {
        assertEquals(42, new Tool.Args(Map.of("x", 42)).getInt("x", 0));
    }

    @Test
    void getIntWithStringNumber() {
        assertEquals(10, new Tool.Args(Map.of("x", "10")).getInt("x", 0));
    }

    @Test
    void getIntWithMissingKey() {
        assertEquals(5, new Tool.Args(Map.of()).getInt("x", 5));
    }

    @Test
    void getIntWithInvalidString() {
        assertEquals(0, new Tool.Args(Map.of("x", "not-a-number")).getInt("x", 0));
    }

    @TempDir
    Path tempDir;

    FakeExtensionAPI api() {
        return new FakeExtensionAPI(new FakeExtensionRuntime());
    }

    @Test
    void initRegistersReadToolAndSlashCommand() {
        var api = api();
        var ext = new ToolOverrideExtension();
        ext.init(api);
        assertEquals(1, api.tools().size());
        assertEquals("read", api.tools().get(0).name());
        assertNotNull(api.slashCommand("read-log"));
    }

    @Test
    void readToolBlocksSensitiveFile() throws Exception {
        var api = api();
        var ext = new ToolOverrideExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        var result = tool.execute().execute("tc", new Tool.Args(Map.of("filePath", ".env")), tempDir.toString()).get(1,
                                        TimeUnit.SECONDS);
        assertTrue(result.isError());
        assertTrue(result.content().contains("Access denied"));
    }

    @Test
    void readToolRequiresFilePath() throws Exception {
        var api = api();
        var ext = new ToolOverrideExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        var result = tool.execute().execute("tc", new Tool.Args(Map.of()), tempDir.toString()).get(1, TimeUnit.SECONDS);
        assertTrue(result.isError());
        assertTrue(result.content().contains("Missing file path"));
    }

    @Test
    void readToolReadsFileWithOffsetAndLimit() throws Exception {
        var api = api();
        var ext = new ToolOverrideExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        var file = tempDir.resolve("sample.txt");
        Files.writeString(file, "line1\nline2\nline3\nline4\n");
        var result = tool.execute().execute("tc",
                                        new Tool.Args(Map.of("filePath", "sample.txt", "offset", 2, "limit", 2)),
                                        tempDir.toString()).get(1, TimeUnit.SECONDS);
        assertFalse(result.isError());
        assertEquals("line2\nline3", result.content());
        assertEquals(4, result.details().get("lines"));
    }

    @Test
    void readToolTruncatesLargeContent() throws Exception {
        var api = api();
        var ext = new ToolOverrideExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        var file = tempDir.resolve("big.txt");
        Files.writeString(file, "x".repeat(60 * 1024));
        var result = tool.execute().execute("tc", new Tool.Args(Map.of("filePath", "big.txt")), tempDir.toString())
                                        .get(1, TimeUnit.SECONDS);
        assertFalse(result.isError());
        assertTrue(result.content().contains("[Output truncated at 50KB]"));
    }

    @Test
    void readToolHandlesMissingFile() throws Exception {
        var api = api();
        var ext = new ToolOverrideExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        var result = tool.execute().execute("tc", new Tool.Args(Map.of("filePath", "missing.txt")), tempDir.toString())
                                        .get(1, TimeUnit.SECONDS);
        assertTrue(result.isError());
    }

    @Test
    void readToolSupportsLegacyPathArg() throws Exception {
        var api = api();
        var ext = new ToolOverrideExtension();
        ext.init(api);
        var tool = api.tools().get(0);
        var file = tempDir.resolve("legacy.txt");
        Files.writeString(file, "content");
        var result = tool.execute().execute("tc", new Tool.Args(Map.of("path", "legacy.txt")), tempDir.toString())
                                        .get(1, TimeUnit.SECONDS);
        assertFalse(result.isError());
        assertEquals("content", result.content());
    }
}
