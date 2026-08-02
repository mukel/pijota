package org.pijota.tui;

/**
 * The <a href="https://sw.kovidgoyal.net/kitty/keyboard-protocol/">Kitty keyboard protocol</a>, which makes modifiers
 * unambiguous. Without it a terminal cannot say "Shift+Enter" at all, and Esc is indistinguishable from the start of an
 * Alt-chord except by waiting a few milliseconds and hoping, which is exactly the guess that misfires over a slow SSH
 * link and leaves the TUI acting on a key nobody pressed.
 *
 * <p>
 * Only flag 1 (disambiguate escape codes) is requested. Flag 2 would add key-release events, which every handler would
 * then have to filter to avoid acting on a keypress twice; nothing here needs them.
 *
 * <p>
 * Nothing is negotiated. Asking the terminal what it supports means blocking the startup path on a reply that may never
 * come, which is the cost {@code graphemeCluster(false)} exists to avoid, and the answer would change nothing: these
 * sequences are ignored by terminals that do not implement them, and {@link InputDecoder} reads the legacy and CSI-u
 * encodings either way. The user-visible test is simpler than a log line -- press Shift+Enter and see whether it
 * inserts a newline.
 */
final class KittyKeyboard {
    private KittyKeyboard() {}

    /** Push flag 1 onto the terminal's keyboard-mode stack. Ignored by terminals without the protocol. */
    static final String ENABLE = "\033[>1u";

    /** Pop our entry. Safe to send unconditionally: on a terminal that never pushed, it is ignored. */
    static final String DISABLE = "\033[<u";
}
