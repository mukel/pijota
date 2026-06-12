package ai.pijota.extensions.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StructuredOutputExtensionTest {

    @Test
    void name() {
        var ext = new StructuredOutputExtension();
        assertEquals("structured-output", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new StructuredOutputExtension();
        assertNotNull(ext.description());
    }
}
