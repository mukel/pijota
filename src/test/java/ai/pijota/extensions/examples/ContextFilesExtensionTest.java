package ai.pijota.extensions.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextFilesExtensionTest {

    @Test
    void nameIsContextFiles() {
        var ext = new ContextFilesExtension("/cwd");
        assertEquals("context-files", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new ContextFilesExtension("/cwd");
        assertNotNull(ext.description());
        assertFalse(ext.description().isBlank());
    }
}
