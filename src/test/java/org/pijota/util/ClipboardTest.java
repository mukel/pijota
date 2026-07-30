package org.pijota.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClipboardTest {

    @Test
    void nullTextReturnsFalse() {
        assertFalse(Clipboard.copy(null));
    }

    @Test
    void copyNeverThrowsRegardlessOfEnvironment() {
        // Success depends on whether a clipboard tool is installed, but the contract is that it degrades to a boolean
        // and never propagates an exception (or an Error, as the old AWT path could in a native image).
        assertDoesNotThrow(() -> Clipboard.copy("pijota clipboard test"));
    }
}
