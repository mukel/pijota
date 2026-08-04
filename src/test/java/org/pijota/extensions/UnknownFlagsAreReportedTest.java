package org.pijota.extensions;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Any {@code --name} is accepted as an extension toggle, which is what makes extensions pluggable and what made a typo
 * indistinguishable from a feature. {@code --readonyl} was filed as an extension flag and the session ran with full
 * write and shell access; {@code --no-bash}, which has never been a flag, read as though it had worked.
 *
 * <p>
 * The parser already refuses to let an unknown {@code --mode} or {@code --thinking} value pass quietly. This is that
 * rule applied to the flag's name.
 */
class UnknownFlagsAreReportedTest {

    /** Minimal extension so the registry has a real name to match against. */
    private record Named(String name) implements Extension {
        @Override
        public String description() {
            return "test";
        }

        @Override
        public boolean enabledByDefault() {
            return false;
        }

        @Override
        public Optional<String> unavailableReason() {
            return Optional.empty();
        }
    }

    private static String warningsFor(Map<String, String> flags, String... extensionNames) {
        var registry = new ExtensionRegistry();
        registry.setExtensionFlags(flags);
        for (String name : extensionNames)
            registry.register(new Named(name));

        PrintStream saved = System.err;
        var captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            registry.warnAboutUnknownFlags(Set.of("web", "telegram", "acp", "tui"));
        } finally {
            System.setErr(saved);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aTypoInASafetyFlagIsReportedRatherThanSilentlyIgnored() {
        String out = warningsFor(Map.of("readonyl", "true"), "todo");
        assertTrue(out.contains("--readonyl"), out);
        assertTrue(out.contains("does nothing"), "it must say the flag had no effect: " + out);
    }

    /** A near-miss is nearly always a typo, and the user cannot see the valid set from the error alone. */
    @Test
    void aNearMissSuggestsTheRealName() {
        assertTrue(warningsFor(Map.of("tod", "true"), "todo").contains("Did you mean '--todo'?"));
        assertTrue(warningsFor(Map.of("todoo", "true"), "todo").contains("Did you mean '--todo'?"));
        assertTrue(warningsFor(Map.of("tovo", "true"), "todo").contains("Did you mean '--todo'?"));
    }

    /** `--no-bash` looks plausible and has never existed; bash is a tool, removed with `--no-tool bash`. */
    @Test
    void noBashIsReportedBecauseBashIsNotAnExtension() {
        assertTrue(warningsFor(Map.of("no-bash", "true"), "todo", "jobs").contains("--no-bash"));
    }

    @Test
    void realFlagsAreNeverReported() {
        assertEquals("", warningsFor(Map.of("todo", "true"), "todo"), "an enabled extension");
        assertEquals("", warningsFor(Map.of("no-todo", "true"), "todo"), "a disabled one still names a real extension");
        assertEquals("", warningsFor(Map.of("browse", "firefox"), "browse"), "a flag carrying a value");
        assertEquals("", warningsFor(Map.of("web", "127.0.0.1:8080"), "todo"), "a frontend subcommand is not one");
    }

    /**
     * {@code --no-todo} disables todo, so it never reaches the registry's enabled list. Validating against that list
     * would report the very flag that did it, which is why the check uses the names offered rather than the ones kept.
     */
    @Test
    void anExtensionTurnedOffByItsOwnFlagIsStillAKnownName() {
        assertEquals("", warningsFor(Map.of("no-todo", "true"), "todo"));
    }

    @Test
    void unrelatedFlagsDoNotDragInEachOthersSuggestions() {
        String out = warningsFor(Map.of("completelyunrelated", "true"), "todo", "jobs");
        assertTrue(out.contains("--completelyunrelated"), out);
        assertFalse(out.contains("Did you mean"), "no near miss, so no guess: " + out);
    }
}
