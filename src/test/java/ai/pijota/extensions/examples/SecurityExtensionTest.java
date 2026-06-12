package ai.pijota.extensions.examples;

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
}
