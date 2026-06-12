package ai.pijota.extensions.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TruncatedToolExtensionTest {

    @Test
    void name() {
        var ext = new TruncatedToolExtension();
        assertEquals("truncated-tool", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new TruncatedToolExtension();
        assertNotNull(ext.description());
    }
}
