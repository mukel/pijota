package org.pijota.tui;

import org.pijota.util.Platform;

/**
 * Renders keyboard-shortcut labels in the notation native to the OS. On macOS the modifier words become the glyphs Mac
 * keyboards are labelled with: ⌃ (Control), ⌥ (Option/Alt), ⇧ (Shift), set tight against the key ({@code ⌃K},
 * {@code ⌥Y}, {@code ⌃⇧P}); on every other platform the written {@code Ctrl+}/{@code Alt+}/{@code Shift+} is kept. Base
 * keys (letters, {@code Tab}, {@code Enter}…) stay as text either way.
 *
 * <p>
 * This only changes how a shortcut is <em>shown</em>, never which key fires it: a terminal delivers Ctrl on every OS
 * (⌘/Cmd never reaches a TUI program), so the bindings are Ctrl-based everywhere; macOS just reads ⌃ for that key.
 */
final class Keys {

    private Keys() {}

    /** A shortcut label, or any sentence containing shortcut tokens, in the current OS's notation. */
    static String label(String s) {
        return render(s, Platform.isMac());
    }

    /** Pure core, split out so the mac/non-mac branches are unit-testable without touching the real {@code os.name}. */
    static String render(String s, boolean mac) {
        return mac ? s.replace("Ctrl+", "⌃").replace("Alt+", "⌥").replace("Shift+", "⇧") : s;
    }
}
