package org.pijota.extensions.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatusLineExtensionTest {

    @Test
    void name() {
        var ext = new StatusLineExtension();
        assertEquals("status-line", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new StatusLineExtension();
        assertNotNull(ext.description());
    }
}
