package org.pijota.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SystemPromptTest {

    /** Everything a default run has, as AppBootstrap would pass it. */
    private static final Set<String> ALL = Set.of("read", "edit", "write", "glob", "grep", "ls", "bash");

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
        var prompt = SystemPrompt.build(null, null, "/cwd", "/opt/pijota-shell", List.of(), ALL);
        assertTrue(prompt.contains("Shell: /opt/pijota-shell"));
    }

    /**
     * With no tools sent, naming them is an instruction the model cannot follow: it answers by writing a tool call as
     * prose in its own markup (deepseek-v4-flash emits a DSML block), which is what the reader then sees.
     */
    @Test
    void withoutToolsThePromptDoesNotNameAnyToolToCall() {
        var prompt = SystemPrompt.build(null, null, "/cwd", "/bin/test-shell", List.of(), Set.of());

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
        var prompt = SystemPrompt.build(null, null, "/cwd", "/bin/test-shell", List.of(), ALL);

        assertTrue(prompt.contains("TOOL USE"), prompt);
        assertTrue(prompt.contains("Read a file before editing it"), prompt);
        assertTrue(prompt.contains("Never commit unless explicitly asked"),
                                        "shared rules survive the split: " + prompt);
    }

    /** --no-builtin-tools: extension tools survive, and every tool the fixed text used to name is gone. */
    @Test
    void withOnlyExtensionToolsNoBuiltinIsNamed() {
        var prompt = SystemPrompt.build(null, null, "/cwd", "/bin/test-shell", List.of(), Set.of("mcp_query", "task"));

        assertFalse(prompt.contains("bash"), "built-ins are gone under -nbt: " + prompt);
        assertFalse(prompt.contains("glob"), prompt);
        assertFalse(prompt.contains("Read a file before editing it"), prompt);
        assertFalse(prompt.contains("EXPLORATION GUIDELINES"), "nothing left to say there: " + prompt);
        // it still has tools, so the tool-agnostic advice remains and the no-tools claim must not appear
        assertTrue(prompt.contains("Make every tool call count"), prompt);
        assertFalse(prompt.contains("This run has no tools"), prompt);
    }

    /** --read-only: read and glob survive, edit and write do not, so their advice must not appear. */
    @Test
    void readOnlyToolsGetNoEditOrWriteAdvice() {
        var prompt = SystemPrompt.build(null, null, "/cwd", "/bin/test-shell", List.of(),
                                        Set.of("read", "ls", "grep", "glob"));

        assertTrue(prompt.contains("glob"), prompt);
        assertTrue(prompt.contains("Use ls only when"), prompt);
        assertFalse(prompt.contains("Read a file before editing it"), "no edit tool in a read-only run: " + prompt);
        assertFalse(prompt.contains("Create parent directories"), "no write tool either: " + prompt);
        assertFalse(prompt.contains("Run lint/typecheck"), "nor bash: " + prompt);
        assertFalse(prompt.contains("Prefer the dedicated tools"), "that advice is about preferring them over bash");
    }

    /** The named list is the run's own tools, not a fixed one. */
    @Test
    void theDedicatedListNamesOnlyWhatIsPresent() {
        var prompt = SystemPrompt.build(null, null, "/cwd", "/bin/test-shell", List.of(),
                                        Set.of("read", "glob", "bash"));

        assertTrue(prompt.contains("Prefer the dedicated tools (read, glob)"), prompt);
        assertFalse(prompt.contains("edit"), prompt);
        assertFalse(prompt.contains("write"), prompt);
    }

    private static String build(String customPrompt, String appendPrompt, List<String> extensionPrompts) {
        return SystemPrompt.build(customPrompt, appendPrompt, "/cwd", "/bin/test-shell", extensionPrompts, ALL);
    }
}
