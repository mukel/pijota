package ai.pijota.agent;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemPromptTest {

    @Test
    void buildProducesBasePromptAndEnvironment() {
        var prompt = SystemPrompt.build(null, null, "/cwd", List.of());
        assertTrue(prompt.contains("You are pijota"));
        assertTrue(prompt.contains("ENVIRONMENT"));
        assertTrue(prompt.contains("Working directory: /cwd"));
    }

    @Test
    void transcendenceSectionTogglesWithFlag() {
        assertTrue(SystemPrompt.build(null, null, "/cwd", List.of(), true).contains("TRANSCENDENCE:"));
        assertFalse(SystemPrompt.build(null, null, "/cwd", List.of(), false).contains("TRANSCENDENCE:"));
        // the no-flag overload keeps it (built-in, on by default)
        assertTrue(SystemPrompt.build(null, null, "/cwd", List.of()).contains("TRANSCENDENCE:"));
    }

    @Test
    void doesNotDuplicateToolListing() {
        // Tool names/descriptions live in the tool manifest, not the system prompt.
        var prompt = SystemPrompt.build(null, null, "/cwd", List.of());
        assertFalse(prompt.contains("TOOLS:"));
        assertFalse(prompt.contains("TOOL GUIDELINES"));
    }

    @Test
    void buildIncludesCustomPromptWhenProvided() {
        var prompt = SystemPrompt.build("be careful", null, "/cwd", List.of());
        assertTrue(prompt.contains("CUSTOM INSTRUCTIONS"));
        assertTrue(prompt.contains("be careful"));
    }

    @Test
    void buildSkipsBlankCustomPrompt() {
        assertFalse(SystemPrompt.build("  ", null, "/cwd", List.of()).contains("CUSTOM INSTRUCTIONS"));
    }

    @Test
    void buildSkipsNullCustomPrompt() {
        assertFalse(SystemPrompt.build(null, null, "/cwd", List.of()).contains("CUSTOM INSTRUCTIONS"));
    }

    @Test
    void buildIncludesAppendPrompt() {
        var prompt = SystemPrompt.build(null, "extra rule", "/cwd", List.of());
        assertTrue(prompt.contains("ADDITIONAL INSTRUCTIONS"));
        assertTrue(prompt.contains("extra rule"));
    }

    @Test
    void buildSkipsBlankOrNullAppendPrompt() {
        assertFalse(SystemPrompt.build(null, null, "/cwd", List.of()).contains("ADDITIONAL INSTRUCTIONS"));
        assertFalse(SystemPrompt.build(null, "", "/cwd", List.of()).contains("ADDITIONAL INSTRUCTIONS"));
        assertFalse(SystemPrompt.build(null, "  ", "/cwd", List.of()).contains("ADDITIONAL INSTRUCTIONS"));
    }

    @Test
    void buildIncludesExtensionPrompts() {
        var prompt = SystemPrompt.build(null, null, "/cwd", List.of("\nextension content\n"));
        assertTrue(prompt.contains("extension content"));
    }

    @Test
    void buildSkipsBlankExtensionPrompts() {
        var prompt = SystemPrompt.build(null, null, "/cwd", new java.util.ArrayList<>(List.of("  ", "\n")));
        assertFalse(prompt.contains("ADDITIONAL INSTRUCTIONS"));
    }
}
