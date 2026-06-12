package ai.pijota.extensions.examples;

import java.util.Map;

import org.junit.jupiter.api.Test;

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
        assertEquals(42, ToolOverrideExtension.getInt(Map.of("x", 42), "x", 0));
    }

    @Test
    void getIntWithStringNumber() {
        assertEquals(10, ToolOverrideExtension.getInt(Map.of("x", "10"), "x", 0));
    }

    @Test
    void getIntWithMissingKey() {
        assertEquals(5, ToolOverrideExtension.getInt(Map.of(), "x", 5));
    }

    @Test
    void getIntWithInvalidString() {
        assertEquals(0, ToolOverrideExtension.getInt(Map.of("x", "not-a-number"), "x", 0));
    }
}
