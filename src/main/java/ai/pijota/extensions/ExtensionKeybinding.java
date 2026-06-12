package ai.pijota.extensions;

public record ExtensionKeybinding(KeyChord key, String description, Runnable handler) {
}
