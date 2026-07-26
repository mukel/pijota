package org.pijota.extensions.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GitCheckpointExtensionTest {

    @Test
    void name() {
        var ext = new GitCheckpointExtension();
        assertEquals("git-checkpoint", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new GitCheckpointExtension();
        assertNotNull(ext.description());
    }
}
