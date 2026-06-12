package ai.pijota.extensions;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

public record KeyChord(Key key, Set<Modifier> modifiers) {

    public KeyChord(Key key, Modifier... modifiers) {
        this(key, modifiers.length == 0 ? EnumSet.noneOf(Modifier.class) : EnumSet.copyOf(Arrays.asList(modifiers)));
    }

    public KeyChord {
        modifiers = Set.copyOf(modifiers);
    }

    public enum Key {
        A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, ENTER, BACKSPACE, DELETE, SPACE, TAB, ESC, UP, DOWN, LEFT, RIGHT, PG_UP, PG_DOWN, HOME, END, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
    }

    public enum Modifier {
        CTRL, SHIFT, ALT
    }
}
