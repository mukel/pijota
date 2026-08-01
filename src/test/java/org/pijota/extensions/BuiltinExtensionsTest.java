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
}
