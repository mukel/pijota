package org.pijota.testkit;

import org.pijota.config.ProjectTrust;
import org.pijota.config.ProviderCatalog;
import org.pijota.llm.ModelInfo;
import org.pijota.llm.ModelRegistry;
import org.pijota.tools.SlashCommandTool;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.List;

/**
 * Puts the process-wide state back after every test.
 *
 * <p>
 * A handful of things in pijota are static by design: the model catalogue, the resolved trust decision for this run,
 * the user-provider overlay, and the question handler belonging to whichever UI is attached. One process, one answer.
 * Surefire reuses its forks, so a test that changes one of them hands it to whatever runs next in that JVM, and nothing
 * in the class it lands in mentions the dependency.
 *
 * <p>
 * That has already cost a build: {@code ExtensionRegistry.setTui} installs the question handler globally, a test left
 * one behind, and the next test asking a question waited on a UI that would never answer, on a {@code get()} with no
 * timeout. It parked for 78 minutes. The failure appears in an innocent class, in whatever order that day's fork
 * assignment produced, so it reads as flakiness rather than as the leak it is.
 *
 * <p>
 * Registered for every test through {@code junit-platform.properties} rather than per class, because the tests that
 * need it are exactly the ones that do not know they do.
 */
public final class IsolatedGlobals implements BeforeEachCallback, AfterEachCallback {

    private List<ModelInfo> models;
    private String trustedCwd;

    @Override
    public void beforeEach(ExtensionContext context) {
        models = ModelRegistry.KNOWN_MODELS;
        trustedCwd = null;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ModelRegistry.KNOWN_MODELS = models;
        // Write-only from here, so the reset is to "nothing attached", which is the state a fresh process starts in.
        SlashCommandTool.setQuestionHandler(null);
        ProviderCatalog.registerUserProviders(List.of());
        // resolve() is the only way in; an untrusted answer for a path nothing else uses is the closest thing to
        // clearing it, and it stops a trusted decision travelling to the next test.
        ProjectTrust.resolve(trustedCwd == null ? "/nonexistent-pijota-test-reset" : trustedCwd, false);
    }
}
