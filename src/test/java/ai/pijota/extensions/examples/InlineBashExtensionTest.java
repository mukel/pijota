package ai.pijota.extensions.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InlineBashExtensionTest {

    @Test
    void name() {
        var ext = new InlineBashExtension();
        assertEquals("inline-bash", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new InlineBashExtension();
        assertNotNull(ext.description());
    }
}
