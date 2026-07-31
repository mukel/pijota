package org.pijota.extensions;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/** A key plus a set of modifier keys (Ctrl, Shift, Alt) — used with {@link ExtensionKeybinding}. */
public record KeyChord(Key key, Set<Modifier> modifiers) {

    public KeyChord(Key key, Modifier... modifiers) {
        this(key, modifiers.length == 0 ? EnumSet.noneOf(Modifier.class) : EnumSet.copyOf(Arrays.asList(modifiers)));
    }

    public KeyChord {
        modifiers = Set.copyOf(modifiers);
    }

    /** Human-readable form like {@code Ctrl+Alt+P} (written modifiers; {@code org.pijota.tui.Keys} maps them to ⌃⌥⇧ on macOS). */
    public String display() {
        var sb = new StringBuilder();
        if (modifiers.contains(Modifier.CTRL))
            sb.append("Ctrl+");
        if (modifiers.contains(Modifier.ALT))
            sb.append("Alt+");
        if (modifiers.contains(Modifier.SHIFT))
            sb.append("Shift+");
        return sb.append(keyLabel()).toString();
    }

    private String keyLabel() {
        return switch (key) {
            case ENTER -> "Enter";
            case BACKSPACE -> "Backspace";
            case DELETE -> "Delete";
            case SPACE -> "Space";
            case TAB -> "Tab";
            case ESC -> "Esc";
            case UP -> "Up";
            case DOWN -> "Down";
            case LEFT -> "Left";
            case RIGHT -> "Right";
            case PG_UP -> "PgUp";
            case PG_DOWN -> "PgDn";
            case HOME -> "Home";
            case END -> "End";
            default -> key.name(); // A-Z and F1-F12 read fine as-is
        };
    }

    public enum Key {
        A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, ENTER, BACKSPACE, DELETE, SPACE, TAB, ESC, UP, DOWN, LEFT, RIGHT, PG_UP, PG_DOWN, HOME, END, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
    }

    public enum Modifier {
        CTRL, SHIFT, ALT
    }
}
