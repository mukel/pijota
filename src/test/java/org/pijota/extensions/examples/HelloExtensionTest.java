package org.pijota.extensions.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HelloExtensionTest {

    @Test
    void nameIsHello() {
        var ext = new HelloExtension();
        assertEquals("hello", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new HelloExtension();
        assertNotNull(ext.description());
        assertFalse(ext.description().isBlank());
    }
}
