package org.pijota.extensions;

/** A key combination bound to a handler, registered by extensions for global TUI keybindings. */
public record ExtensionKeybinding(KeyChord key, String description, Runnable handler) {
}
