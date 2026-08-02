package org.pijota.extensions;

import static org.junit.jupiter.api.Assertions.*;

import org.pijota.extensions.examples.ContextFilesExtension;
import org.pijota.extensions.jobs.JobsExtension;
import org.pijota.extensions.prompts.PromptTemplateExtension;
import org.pijota.extensions.skills.SkillExtension;
import org.pijota.extensions.tmux.TmuxExtension;

import java.util.List;

import org.junit.jupiter.api.Test;

class BuiltinExtensionsTest {

    /**
     * The catalog claims to be the single source for --help "so it can't drift from reality" -- but nothing enforced
     * that, and "jobs" did drift: --help advertised "--jobs [default: off]" while JobsExtension.enabledByDefault()
     * returned true, so the flag shown was the one that does nothing and --no-jobs was undiscoverable. Only the
     * extensions constructible without live wiring are checked; that is enough to catch a hand-edited boolean.
     */
    @Test
    void catalogDefaultsMatchTheExtensionsThemselves() {
        List<Extension> real = List.of(new JobsExtension(), new TmuxExtension(), new SkillExtension(List.of()),
                                        new PromptTemplateExtension(List.of()), new ContextFilesExtension("/tmp"));
        var catalog = BuiltinExtensions.TOGGLEABLE.stream().collect(java.util.stream.Collectors
                                        .toMap(BuiltinExtensions.Spec::name, BuiltinExtensions.Spec::enabledByDefault));
        for (var ext : real) {
            Boolean declared = catalog.get(ext.name());
            assertNotNull(declared, ext.name() + " is toggleable but missing from the --help catalog");
            assertEquals(ext.enabledByDefault(), declared.booleanValue(),
                                            "--help would advertise the wrong default (and the wrong flag) for "
                                                                            + ext.name());
        }
    }

    /** A default-on extension must be advertised with the flag that turns it OFF; that is the only useful one. */
    @Test
    void helpShowsTheFlagThatActuallyChangesSomething() {
        String help = BuiltinExtensions.helpSection();
        assertTrue(help.contains("--no-jobs"), "jobs is on by default, so --help must show --no-jobs: " + help);
        assertTrue(help.contains("--tmux"), "tmux is off by default, so --help must show --tmux: " + help);
        for (var spec : BuiltinExtensions.TOGGLEABLE) {
            String shown = spec.enabledByDefault() ? "--no-" + spec.name() : "--" + spec.name();
            assertTrue(help.contains(shown + " ") || help.contains(shown + "\n"), "missing " + shown + " in:\n" + help);
        }
    }

    /**
     * {@code --help} is read in a terminal, and 80 columns is the one width every terminal has. Unwrapped, the longest
     * of these entries ran to 127 columns, so the text folded back to column zero and the flag column stopped being a
     * column at exactly the moment someone is scanning it for a flag name.
     */
    @Test
    void everyHelpLineFitsAnEightyColumnTerminal() {
        for (String line : BuiltinExtensions.helpSection().split("\n"))
            assertTrue(line.length() <= 80, line.length() + " columns: " + line);
    }

    /** Wrapped descriptions hang under the description, not under the flag, or the flag column reads as ragged. */
    @Test
    void wrappedDescriptionsStayInTheDescriptionColumn() {
        int column = -1;
        for (String line : BuiltinExtensions.helpSection().split("\n")) {
            if (line.startsWith("  --")) {
                int at = line.indexOf(' ', 2);
                while (at < line.length() && line.charAt(at) == ' ')
                    at++;
                if (column < 0)
                    column = at;
                assertEquals(column, at, "description column drifted: " + line);
            } else if (column > 0 && !line.isBlank()) { // a continuation of the entry above
                assertEquals(column, line.indexOf(line.strip()), "continuation hangs off the column: " + line);
            }
        }
        assertTrue(column > 0, "no entries found; the help shape changed");
    }
}
