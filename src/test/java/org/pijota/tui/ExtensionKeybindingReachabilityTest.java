package org.pijota.tui;

import org.pijota.extensions.KeyChord;
import org.pijota.extensions.examples.plan.PlanExtension;
import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeExtensionRuntime;
import org.pijota.extensions.transcend.TranscendenceExtension;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An extension can declare any {@link KeyChord} it likes, but the TUI can only deliver one the input decoder actually
 * produces: a keypress becomes a {@link Key.Special}, and {@code AppendOnlyTui.toKeyChord} maps that to a chord. A
 * chord outside that image is unreachable -- the handler never runs -- yet it was still listed in {@code /help}, which
 * is the same defect shape as a flag that {@code --help} advertises and nothing reads.
 *
 * <p>
 * Both extensions that ship a keybinding had exactly that: {@code PlanExtension} bound Ctrl+Alt+P and
 * {@code TranscendenceExtension} Ctrl+Alt+G, and nothing in the decoder could produce a Ctrl+Alt chord. So every
 * extension keybinding in the tree was dead, and both were advertised.
 */
class ExtensionKeybindingReachabilityTest {

    private static Set<String> deliverable() {
        return AppendOnlyTui.deliverableChords().stream().map(KeyChord::display)
                                        .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void theChordVocabularyIsNotEmpty() {
        assertFalse(deliverable().isEmpty(), "toKeyChord maps nothing; the reachability check would be vacuous");
    }

    /**
     * The check an extension author needs, and the one the TUI now applies at registration: every chord a shipped
     * extension binds must be one the decoder can produce.
     */
    @Test
    void everyChordShippedExtensionsBindIsDeliverable() throws Exception {
        var extensions = List.of(new PlanExtension(), new TranscendenceExtension());
        int bound = 0;
        for (var extension : extensions) {
            var api = new FakeExtensionAPI(new FakeExtensionRuntime());
            extension.init(api);
            for (var binding : api.keybindings()) {
                bound++;
                assertTrue(deliverable().contains(binding.key().display()), extension.name() + " binds "
                                                + binding.key().display() + ", which no key can produce, so its "
                                                + "handler never runs while /help " + "advertises it");
            }
        }
        assertTrue(bound > 0, "no extension registered a keybinding; this check went vacuous");
    }

    /** Ctrl+Alt is the extension keyspace: every plain Ctrl+letter is already claimed by the editor or the TUI. */
    @Test
    void theCtrlAltKeyspaceIsReachableInBothEncodings() {
        assertEquals(Key.Special.CTRL_ALT_P, InputDecoder.ctrlAltKey('p'));
        assertEquals(Key.Special.CTRL_ALT_G, InputDecoder.ctrlAltKey('G'), "case does not matter");
        assertNull(InputDecoder.ctrlAltKey('q'), "a letter nothing binds stays undecoded rather than swallowed");
    }

    /**
     * A chord outside the decoder's image is still unreachable; the registration guard is what keeps it out of /help.
     */
    @Test
    void aChordWithNoDecoderPathIsStillNotDeliverable() {
        var ctrlAltQ = new KeyChord(KeyChord.Key.Q, KeyChord.Modifier.CTRL, KeyChord.Modifier.ALT);
        assertFalse(deliverable().contains(ctrlAltQ.display()),
                                        "Ctrl+Alt+Q has no decoder path; binding it must not reach /help");
    }

    @Test
    void theChordsTheTuiItselfUsesAreDeliverable() {
        assertTrue(deliverable().contains(new KeyChord(KeyChord.Key.P, KeyChord.Modifier.CTRL).display()));
        assertTrue(deliverable().contains(new KeyChord(KeyChord.Key.P, KeyChord.Modifier.CTRL, KeyChord.Modifier.SHIFT)
                                        .display()));
    }

    /** Reserved keys never reach an extension, so they are unusable for a different reason and stay out of the set. */
    @Test
    void reservedKeysAreNotOfferedToExtensions() {
        assertTrue(AppendOnlyTui.isReservedKey(Key.Special.TAB), "Tab decodes fine, but the TUI keeps it");
        assertTrue(AppendOnlyTui.isReservedKey(Key.Special.ENTER));
        assertFalse(deliverable().contains(new KeyChord(KeyChord.Key.TAB).display()),
                                        "a reserved key must not be offered as bindable");
    }
}
