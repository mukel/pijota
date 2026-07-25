package ai.pijota.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToolOutputStoreTest {
    @Test
    void boundLeavesSmallOutputInline() throws Exception {
        var stored = ToolOutputStore.bound("short", 100, "test-");

        assertEquals("short", stored.text());
        assertTrue(stored.details().isEmpty());
    }

    @Test
    void boundStoresLargeOutputAndReturnsPreview() throws Exception {
        String text = "abcdef\n".repeat(1000);

        var stored = ToolOutputStore.bound(text, 200, "test-");

        assertNotEquals(text, stored.text());
        assertTrue(stored.text().contains("truncated"));
        assertEquals(true, stored.details().get("truncated"));
        Path path = Path.of((String) stored.details().get("outputPath"));
        assertEquals(text, Files.readString(path));
        assertTrue(path.toString().contains(".pijota"));
        assertTrue(path.toString().contains("tool-output"));
    }
}
