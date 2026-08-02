package org.pijota.tui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class KittyKeyboardTest {

    /**
     * These two strings are the whole feature, so they are pinned. Nothing is negotiated: asking the terminal what it
     * supports would block startup on a reply that may never arrive, and the answer would change nothing, since a
     * terminal without the protocol ignores both sequences and the decoder reads either encoding regardless.
     */
    @Test
    void enableAndDisableAreTheStackPushAndPopSequences() {
        assertEquals("\033[>1u", KittyKeyboard.ENABLE, "flag 1 only: key-release events would double-fire handlers");
        assertEquals("\033[<u", KittyKeyboard.DISABLE);
    }
}
