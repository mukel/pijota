package ai.pijota.tui;

sealed interface Key {
    record Char(char value) implements Key {
    }
    record Paste(String text) implements Key {
    }
    enum Special implements Key {
        ENTER, NEWLINE, BACKSPACE, DELETE, LEFT, RIGHT, UP, DOWN, HOME, END, PG_UP, PG_DOWN, TAB, SHIFT_TAB, CTRL_A, CTRL_C, CTRL_D, CTRL_E, CTRL_G, CTRL_K, CTRL_L, CTRL_P, CTRL_R, CTRL_T, CTRL_SHIFT_P, CTRL_U, CTRL_W, CTRL_Y, CTRL_UNDERSCORE, ALT_D, ALT_Y, ALT_UNDERSCORE, CTRL_LEFT, CTRL_RIGHT, CTRL_X, CTRL_Z, ESC, DOUBLE_ESC, PASTE_START, UNKNOWN
    }
}
