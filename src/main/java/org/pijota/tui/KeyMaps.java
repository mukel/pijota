package org.pijota.tui;

import org.pijota.extensions.KeyChord;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keys as data: one keypress translated to the chord an extension binds against, and the remap tables the editor and
 * the selectors are built with.
 *
 * <p>
 * All of it was already static on {@link AppendOnlyTui} -- it never touched a field there, which is what made it
 * separable. Beside {@link UserKeybindings} and {@link Keys} it is also where a reader looks for it. The reachability
 * check that keeps extension bindings honest drives {@code toKeyChord} directly, so this is the shape it was already
 * being tested in.
 */
final class KeyMaps {

    private KeyMaps() {}

    static Map<Key.Special, Key.Special> buildEditorKeyRemaps(UserKeybindings keys) {
        return keys.remaps(Map.ofEntries(Map.entry("tui.editor.cursorUp", Key.Special.UP),
                                        Map.entry("tui.editor.cursorDown", Key.Special.DOWN),
                                        Map.entry("tui.editor.cursorLeft", Key.Special.LEFT),
                                        Map.entry("tui.editor.cursorRight", Key.Special.RIGHT),
                                        Map.entry("tui.editor.cursorLineStart", Key.Special.HOME),
                                        Map.entry("tui.editor.cursorLineEnd", Key.Special.END),
                                        Map.entry("tui.editor.cursorWordLeft", Key.Special.CTRL_LEFT),
                                        Map.entry("tui.editor.cursorWordRight", Key.Special.CTRL_RIGHT),
                                        Map.entry("tui.editor.deleteCharBackward", Key.Special.BACKSPACE),
                                        Map.entry("tui.editor.deleteCharForward", Key.Special.DELETE),
                                        Map.entry("tui.editor.deleteWordBackward", Key.Special.CTRL_W),
                                        Map.entry("tui.editor.deleteWordForward", Key.Special.ALT_D),
                                        Map.entry("tui.editor.deleteToLineStart", Key.Special.CTRL_U),
                                        Map.entry("tui.editor.deleteToLineEnd", Key.Special.CTRL_K),
                                        Map.entry("tui.editor.yank", Key.Special.CTRL_Y),
                                        Map.entry("tui.editor.yankPop", Key.Special.ALT_Y),
                                        Map.entry("tui.editor.undo", Key.Special.CTRL_UNDERSCORE),
                                        Map.entry("tui.input.newLine", Key.Special.NEWLINE),
                                        Map.entry("tui.input.submit", Key.Special.ENTER),
                                        Map.entry("tui.input.tab", Key.Special.TAB)));
    }

    static Map<Key.Special, Key.Special> buildSelectorKeyRemaps(UserKeybindings keys) {
        return keys.remaps(Map.ofEntries(Map.entry("tui.select.up", Key.Special.UP),
                                        Map.entry("tui.select.down", Key.Special.DOWN),
                                        Map.entry("tui.select.pageUp", Key.Special.PG_UP),
                                        Map.entry("tui.select.pageDown", Key.Special.PG_DOWN),
                                        Map.entry("tui.select.confirm", Key.Special.ENTER),
                                        Map.entry("tui.select.cancel", Key.Special.ESC)));
    }

    /**
     * The chords an extension binding can actually fire: the image of {@link #toKeyChord} over the keys the decoder
     * produces, minus the ones the TUI reserves for itself and never routes onward. A binding outside this set is
     * unreachable, so it is refused at registration rather than listed in {@code /help} as a shortcut that does
     * nothing.
     */
    static Set<KeyChord> deliverableChords() {
        var out = new HashSet<KeyChord>();
        for (var key : Key.Special.values()) {
            if (isReservedKey(key))
                continue;
            KeyChord chord = toKeyChord(key);
            if (chord != null)
                out.add(chord);
        }
        return out;
    }

    static KeyChord toKeyChord(Key key) {
        if (!(key instanceof Key.Special special))
            return null;
        return switch (special) {
            case ENTER -> new KeyChord(KeyChord.Key.ENTER);
            case BACKSPACE -> new KeyChord(KeyChord.Key.BACKSPACE);
            case DELETE -> new KeyChord(KeyChord.Key.DELETE);
            case TAB -> new KeyChord(KeyChord.Key.TAB);
            case ESC -> new KeyChord(KeyChord.Key.ESC);
            case UP -> new KeyChord(KeyChord.Key.UP);
            case DOWN -> new KeyChord(KeyChord.Key.DOWN);
            case LEFT -> new KeyChord(KeyChord.Key.LEFT);
            case RIGHT -> new KeyChord(KeyChord.Key.RIGHT);
            case PG_UP -> new KeyChord(KeyChord.Key.PG_UP);
            case PG_DOWN -> new KeyChord(KeyChord.Key.PG_DOWN);
            case HOME -> new KeyChord(KeyChord.Key.HOME);
            case END -> new KeyChord(KeyChord.Key.END);
            case CTRL_A -> new KeyChord(KeyChord.Key.A, KeyChord.Modifier.CTRL);
            case CTRL_C -> new KeyChord(KeyChord.Key.C, KeyChord.Modifier.CTRL);
            case CTRL_D -> new KeyChord(KeyChord.Key.D, KeyChord.Modifier.CTRL);
            case CTRL_E -> new KeyChord(KeyChord.Key.E, KeyChord.Modifier.CTRL);
            case CTRL_G -> new KeyChord(KeyChord.Key.G, KeyChord.Modifier.CTRL);
            case CTRL_K -> new KeyChord(KeyChord.Key.K, KeyChord.Modifier.CTRL);
            case CTRL_L -> new KeyChord(KeyChord.Key.L, KeyChord.Modifier.CTRL);
            case CTRL_P -> new KeyChord(KeyChord.Key.P, KeyChord.Modifier.CTRL);
            case CTRL_R -> new KeyChord(KeyChord.Key.R, KeyChord.Modifier.CTRL);
            case CTRL_T -> new KeyChord(KeyChord.Key.T, KeyChord.Modifier.CTRL);
            case CTRL_U -> new KeyChord(KeyChord.Key.U, KeyChord.Modifier.CTRL);
            case CTRL_W -> new KeyChord(KeyChord.Key.W, KeyChord.Modifier.CTRL);
            case CTRL_SHIFT_P -> new KeyChord(KeyChord.Key.P, KeyChord.Modifier.CTRL, KeyChord.Modifier.SHIFT);
            case CTRL_ALT_G -> new KeyChord(KeyChord.Key.G, KeyChord.Modifier.CTRL, KeyChord.Modifier.ALT);
            case CTRL_ALT_P -> new KeyChord(KeyChord.Key.P, KeyChord.Modifier.CTRL, KeyChord.Modifier.ALT);
            case CTRL_LEFT -> new KeyChord(KeyChord.Key.LEFT, KeyChord.Modifier.CTRL);
            case CTRL_RIGHT -> new KeyChord(KeyChord.Key.RIGHT, KeyChord.Modifier.CTRL);
            case SHIFT_TAB -> new KeyChord(KeyChord.Key.TAB, KeyChord.Modifier.SHIFT);
            default -> null;
        };
    }

    static boolean isReservedKey(Key key) {
        return key == Key.Special.ESC || key == Key.Special.DOUBLE_ESC || key == Key.Special.CTRL_C
                                        || key == Key.Special.ENTER || key == Key.Special.NEWLINE
                                        || key == Key.Special.BACKSPACE || key == Key.Special.TAB
                                        || key == Key.Special.PASTE_START;
    }
}
