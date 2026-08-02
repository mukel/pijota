package org.pijota.tui;

sealed interface Key {
    record Char(char value) implements Key {
    }
    record Paste(String text) implements Key {
    }
    enum Special implements Key {
        ENTER, NEWLINE, BACKSPACE, DELETE, LEFT, RIGHT, UP, DOWN, HOME, END, PG_UP, PG_DOWN, TAB, SHIFT_TAB, ALT_ENTER, ALT_UP, CTRL_A, CTRL_B, CTRL_C, CTRL_D, CTRL_E, CTRL_G, CTRL_K, CTRL_L, CTRL_P, CTRL_O, CTRL_R, CTRL_T, CTRL_SHIFT_P, CTRL_U, CTRL_W, CTRL_Y, CTRL_UNDERSCORE, ALT_D, ALT_Y, ALT_UNDERSCORE, CTRL_LEFT, CTRL_RIGHT, CTRL_X, CTRL_Z,
        // Ctrl+Alt: the extension keyspace. Every Ctrl+letter the decoder produces is already claimed by the editor or
        // the global map, so an extension binding one would shadow it. Add a letter here and to InputDecoder.ctrlAltKey
        // to open it up.
        CTRL_ALT_G, CTRL_ALT_P, ESC, DOUBLE_ESC, PASTE_START, UNKNOWN
    }
}
