package org.pijota.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SystemPromptTest {

    @Test
    void buildProducesBasePromptAndEnvironment() {
        var prompt = build(null, null, List.of());
        assertTrue(prompt.contains("You are pijota"));
        assertTrue(prompt.contains("ENVIRONMENT"));
        assertTrue(prompt.contains("Working directory: /cwd"));
        assertTrue(prompt.contains("PIJOTA_* environment variables"));
    }

    @Test
    void optionalCapabilityGuidanceComesFromExtensionPrompts() {
        assertFalse(build(null, null, List.of()).contains("TRANSCENDENCE:"));

        var prompt = build(null, null, List.of("\nTRANSCENDENCE:\nextension-owned\n"));

        assertTrue(prompt.contains("TRANSCENDENCE:"));
    }

    @Test
    void doesNotDuplicateToolListing() {
        // Tool names/descriptions live in the tool manifest, not the system prompt.
        var prompt = build(null, null, List.of());
        assertFalse(prompt.contains("TOOLS:"));
        assertFalse(prompt.contains("TOOL GUIDELINES"));
    }

    @Test
    void buildIncludesCustomPromptWhenProvided() {
        var prompt = build("be careful", null, List.of());
        assertTrue(prompt.contains("CUSTOM INSTRUCTIONS"));
        assertTrue(prompt.contains("be careful"));
    }

    @Test
    void buildSkipsBlankCustomPrompt() {
        assertFalse(build("  ", null, List.of()).contains("CUSTOM INSTRUCTIONS"));
    }

    @Test
    void buildSkipsNullCustomPrompt() {
        assertFalse(build(null, null, List.of()).contains("CUSTOM INSTRUCTIONS"));
    }

    @Test
    void buildIncludesAppendPrompt() {
        var prompt = build(null, "extra rule", List.of());
        assertTrue(prompt.contains("ADDITIONAL INSTRUCTIONS"));
        assertTrue(prompt.contains("extra rule"));
    }

    @Test
    void buildSkipsBlankOrNullAppendPrompt() {
        assertFalse(build(null, null, List.of()).contains("ADDITIONAL INSTRUCTIONS"));
        assertFalse(build(null, "", List.of()).contains("ADDITIONAL INSTRUCTIONS"));
        assertFalse(build(null, "  ", List.of()).contains("ADDITIONAL INSTRUCTIONS"));
    }

    @Test
    void buildIncludesExtensionPrompts() {
        var prompt = build(null, null, List.of("\nextension content\n"));
        assertTrue(prompt.contains("extension content"));
    }

    @Test
    void buildSkipsBlankExtensionPrompts() {
        var prompt = build(null, null, new ArrayList<>(List.of("  ", "\n")));
        assertFalse(prompt.contains("ADDITIONAL INSTRUCTIONS"));
    }

    @Test
    void buildReportsConfiguredShell() {
        var prompt = SystemPrompt.build(null, null, "/cwd", "/opt/pijota-shell", List.of(), true);
        assertTrue(prompt.contains("Shell: /opt/pijota-shell"));
    }

    /**
     * With no tools sent, naming them is an instruction the model cannot follow: it answers by writing a tool call as
     * prose in its own markup (deepseek-v4-flash emits a DSML block), which is what the reader then sees.
     */
    @Test
    void withoutToolsThePromptDoesNotNameAnyToolToCall() {
        var prompt = SystemPrompt.build(null, null, "/cwd", "/bin/test-shell", List.of(), false);

        assertFalse(prompt.contains("TOOL USE"), prompt);
        assertFalse(prompt.contains("EXPLORATION GUIDELINES"), prompt);
        assertFalse(prompt.contains("bash"), "a toolless run must not be told to use bash: " + prompt);
        assertFalse(prompt.contains("glob"), "nor glob: " + prompt);
        // Dropping the guidance is not enough: the model still reaches for a tool and writes the call as prose, so
        // the absence is stated outright.
        assertTrue(prompt.contains("This run has no tools"), prompt);
        assertTrue(prompt.contains("Never write a tool call"), prompt);
        // the guidance that still holds without tools stays
        assertTrue(prompt.contains("Never commit unless explicitly asked"), prompt);
        assertTrue(prompt.contains("Be concise and direct"), prompt);
        assertTrue(prompt.contains("Working directory: /cwd"), prompt);
    }

    @Test
    void withToolsTheToolGuidanceIsPresent() {
        var prompt = SystemPrompt.build(null, null, "/cwd", "/bin/test-shell", List.of(), true);

        assertTrue(prompt.contains("TOOL USE"), prompt);
        assertTrue(prompt.contains("Read a file before editing it"), prompt);
        assertTrue(prompt.contains("Never commit unless explicitly asked"),
                                        "shared rules survive the split: " + prompt);
    }

    private static String build(String customPrompt, String appendPrompt, List<String> extensionPrompts) {
        return SystemPrompt.build(customPrompt, appendPrompt, "/cwd", "/bin/test-shell", extensionPrompts, true);
    }
}
