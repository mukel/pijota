package org.pijota;

import org.pijota.cli.ArgParser;
import org.pijota.config.AppConfig;
import org.pijota.config.SettingsManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code default_project_trust} decides whether a folder's own code runs, and it had no test at all: only its
 * validation warning did. It is the one setting where being wrong means executing something the user did not agree to,
 * so each of its three values is pinned here, along with the flags that override it.
 *
 * <p>
 * {@code pijota.home} is redirected at a temp directory throughout, because the resolver writes trust decisions and
 * must never reach the real {@code ~/.pijota/trust.json} from a test.
 */
class ProjectTrustFallbackTest {

    @TempDir
    Path home;
    @TempDir
    Path project;
    private String savedHome;

    @BeforeEach
    void redirectHome() throws java.io.IOException {
        savedHome = System.setProperty("pijota.home", home.toString());
        // A folder is only a trust question when it carries something executable; settings.json is on that list.
        Files.createDirectories(project.resolve(".pijota"));
        Files.writeString(project.resolve(".pijota").resolve("settings.json"), "{}");
    }

    @AfterEach
    void restoreHome() {
        if (savedHome != null)
            System.setProperty("pijota.home", savedHome);
        else
            System.clearProperty("pijota.home");
    }

    /** Nobody to ask: what a non-interactive run, or an interactive one with no console, sees. */
    private boolean resolve(String fallback, String... argv) {
        return resolve(fallback, cwd -> false, argv);
    }

    private boolean resolve(String fallback, Predicate<String> ask, String... argv) {
        var config = new AppConfig().toBuilder().defaultProjectTrust(fallback).build();
        return AppBootstrap.resolveProjectTrust(project.toString(), ArgParser.parse(argv),
                                        new SettingsManager(config, ignored -> {}), ask);
    }

    @Test
    void askDoesNotTrustWhenNobodyCanBeAsked() {
        assertFalse(resolve("ask", "-p", "hi"), "the default must not run project code unprompted");
        assertFalse(resolve(null, "-p", "hi"), "and unset means ask");
    }

    /**
     * The whole point of never: even face to face, the question is not asked. Asserted with a prompt that would say
     * yes, because with one that says no this passes whether the never branch exists or not -- as an earlier version of
     * this test did.
     */
    @Test
    void neverDoesNotEvenAsk() {
        var asked = new AtomicBoolean();
        boolean trusted = resolve("never", cwd -> {
            asked.set(true);
            return true;
        });

        assertFalse(trusted);
        assertFalse(asked.get(), "never must not reach the prompt");
    }

    /** Answering yes trusts, and is remembered like `always` -- so each of these needs its own untouched home. */
    @Test
    void yesAtThePromptTrustsAndIsRemembered() {
        assertTrue(resolve("ask", cwd -> true));

        assertTrue(Files.exists(home.resolve("trust.json")), "a yes is a saved decision");
    }

    @Test
    void noAtThePromptDoesNotTrust() {
        assertFalse(resolve("ask", cwd -> false));

        assertFalse(Files.exists(home.resolve("trust.json")), "and leaves nothing behind");
    }

    @Test
    void aPrintRunHasNobodyToAsk() {
        var asked = new AtomicBoolean();

        resolve("ask", cwd -> {
            asked.set(true);
            return true;
        }, "-p", "hi");

        assertFalse(asked.get());
    }

    @Test
    void alwaysTrustsAndRemembers() throws java.io.IOException {
        assertTrue(resolve("always", "-p", "hi"));

        Path trustFile = home.resolve("trust.json");
        assertTrue(Files.exists(trustFile), "always is a saved decision, not a per-run one");
        assertTrue(Files.readString(trustFile).contains(project.getFileName().toString()), Files.readString(trustFile));
    }

    @Test
    void theValueIsReadCaseAndSpaceInsensitively() {
        assertTrue(resolve("  ALWAYS  ", "-p", "hi"), "a settings file people hand-edit should not care");
    }

    @Test
    void anUnknownValueFallsBackToNotTrusting() {
        assertFalse(resolve("yes-please", "-p", "hi"), "an unrecognised value must never mean trust");
    }

    @Test
    void flagsOverrideTheSetting() {
        assertTrue(resolve("never", "-p", "hi", "--approve"), "--approve wins over never");
        assertFalse(resolve("always", "-p", "hi", "--no-approve"), "--no-approve wins over always");
    }

    /** No project resources, nothing to decide: the folder is untrusted and no prompt is owed. */
    @Test
    void aFolderWithNothingProtectedIsNeverATrustQuestion(@TempDir Path bare) {
        var config = new AppConfig().toBuilder().defaultProjectTrust("always").build();

        assertFalse(AppBootstrap.resolveProjectTrust(bare.toString(), ArgParser.parse(new String[] {"-p", "hi"}),
                                        new SettingsManager(config, ignored -> {}), cwd -> true));
    }
}
