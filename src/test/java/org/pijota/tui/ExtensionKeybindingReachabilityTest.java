package org.pijota.tui;

import static org.junit.jupiter.api.Assertions.*;

import org.pijota.extensions.KeyChord;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * An extension can declare any {@link KeyChord} it likes, but the TUI can only deliver one the input decoder actually
 * produces: a keypress becomes a {@link Key.Special}, and {@code AppendOnlyTui.toKeyChord} maps that to a chord. A
 * chord outside that image is unreachable -- the handler never runs -- yet it is still listed in {@code /help}, which
 * is the same defect shape as a flag that {@code --help} advertises and nothing reads.
 *
 * <p>
 * {@code PlanExtension} shipped exactly that: it binds Ctrl+Alt+P and advertises "Toggle plan mode", and nothing in the
 * decoder can produce a Ctrl+Alt chord.
 */
class ExtensionKeybindingReachabilityTest {

    /** Every chord the TUI can actually deliver, derived from the decoder's own key vocabulary. */
    private static Set<String> deliverableChords() {
        return Arrays.stream(Key.Special.values()).map(AppendOnlyTui::toKeyChord).filter(java.util.Objects::nonNull)
                                        .map(KeyChord::display).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void theChordVocabularyIsNotEmpty() {
        assertFalse(deliverableChords().isEmpty(), "toKeyChord maps nothing; the reachability check would be vacuous");
    }

    /**
     * The check an extension author needs: does the TUI deliver this chord? Without it the only feedback is a shortcut
     * that silently does nothing while {@code /help} says otherwise.
     */
    @Test
    void aChordWithNoDecoderPathIsNotDeliverable() {
        var ctrlAltP = new KeyChord(KeyChord.Key.P, KeyChord.Modifier.CTRL, KeyChord.Modifier.ALT);
        assertFalse(deliverableChords().contains(ctrlAltP.display()),
                                        "Ctrl+Alt+P became deliverable; PlanExtension's binding can now fire and this "
                                                                        + "test should assert that instead");
    }

    @Test
    void theChordsExtensionsDoUseAreDeliverable() {
        // Ctrl+P and Ctrl+Shift+P are in the decoder's vocabulary; these are the shapes that work today.
        assertTrue(deliverableChords().contains(new KeyChord(KeyChord.Key.P, KeyChord.Modifier.CTRL).display()));
        assertTrue(deliverableChords().contains(
                                        new KeyChord(KeyChord.Key.P, KeyChord.Modifier.CTRL, KeyChord.Modifier.SHIFT)
                                                                        .display()));
    }

    /** Reserved keys are deliverable but never routed to extensions, so they are unusable for a different reason. */
    @Test
    void reservedKeysAreDeliverableButNeverReachAnExtension() {
        assertTrue(deliverableChords().contains(new KeyChord(KeyChord.Key.TAB).display()), "Tab decodes fine");
        assertTrue(AppendOnlyTui.isReservedKey(Key.Special.TAB), "... but the TUI keeps it");
        assertTrue(AppendOnlyTui.isReservedKey(Key.Special.ENTER));
    }
}
