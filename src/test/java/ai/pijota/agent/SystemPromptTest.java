package ai.pijota.agent;

import ai.pijota.tools.Tool;
import ai.pijota.tools.ToolRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemPromptTest {

    private static Tool fakeTool(String name, String description) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of();
            }

            @Override
            public CompletableFuture<Tool.ToolResult> execute(String id, Map<String, Object> args, String cwd) {
                return CompletableFuture.completedFuture(Tool.ToolResult.success("ok"));
            }
        };
    }

    @Test
    void buildWithoutToolsStillProducesBasePrompt() {
        var registry = new ToolRegistry();
        var prompt = SystemPrompt.build(registry, null, null, "/cwd", List.of());
        assertTrue(prompt.contains("You are pijota"));
        assertTrue(prompt.contains("Working directory: /cwd"));
    }

    @Test
    void buildIncludesToolNamesAndDescriptions() {
        var registry = new ToolRegistry();
        registry.register(fakeTool("read", "Reads a file"));
        var prompt = SystemPrompt.build(registry, null, null, "/tmp", List.of());
        assertTrue(prompt.contains("- `read`:"));
        assertTrue(prompt.contains("Reads a file"));
    }

    @Test
    void buildIncludesEnvironmentBlock() {
        var registry = new ToolRegistry();
        var prompt = SystemPrompt.build(registry, null, null, "/home/user", List.of());
        assertTrue(prompt.contains("ENVIRONMENT"));
        assertTrue(prompt.contains("Working directory: /home/user"));
    }

    @Test
    void buildIncludesCustomPromptWhenProvided() {
        var registry = new ToolRegistry();
        var prompt = SystemPrompt.build(registry, "be careful", null, "/cwd", List.of());
        assertTrue(prompt.contains("CUSTOM INSTRUCTIONS"));
        assertTrue(prompt.contains("be careful"));
    }

    @Test
    void buildSkipsBlankCustomPrompt() {
        var registry = new ToolRegistry();
        var prompt = SystemPrompt.build(registry, "  ", null, "/cwd", List.of());
        assertFalse(prompt.contains("CUSTOM INSTRUCTIONS"));
    }

    @Test
    void buildSkipsNullCustomPrompt() {
        var registry = new ToolRegistry();
        var prompt = SystemPrompt.build(registry, null, null, "/cwd", List.of());
        assertFalse(prompt.contains("CUSTOM INSTRUCTIONS"));
    }

    @Test
    void buildIncludesAppendPrompt() {
        var registry = new ToolRegistry();
        var prompt = SystemPrompt.build(registry, null, "extra rule", "/cwd", List.of());
        assertTrue(prompt.contains("ADDITIONAL INSTRUCTIONS"));
        assertTrue(prompt.contains("extra rule"));
    }

    @Test
    void buildIncludesExtensionPrompts() {
        var registry = new ToolRegistry();
        var prompt = SystemPrompt.build(registry, null, null, "/cwd", List.of("\nextension content\n"));
        assertTrue(prompt.contains("extension content"));
    }

    @Test
    void buildSkipsBlankExtensionPrompts() {
        var registry = new ToolRegistry();
        var prompt = SystemPrompt.build(registry, null, null, "/cwd", new java.util.ArrayList<>(List.of("  ", "\n")));
        assertFalse(prompt.contains("ADDITIONAL INSTRUCTIONS"));
        assertEquals(prompt, SystemPrompt.build(registry, null, null, "/cwd", List.of()));
    }

    @Test
    void buildIncludesReadGuidelines() {
        var registry = new ToolRegistry();
        registry.register(fakeTool("read", "Reads a file"));
        var prompt = SystemPrompt.build(registry, null, null, "/cwd", List.of());
        assertTrue(prompt.contains("Use read to examine files instead of cat or sed"));
    }

    @Test
    void buildIncludesEditGuidelines() {
        var registry = new ToolRegistry();
        registry.register(fakeTool("edit", "Edits a file"));
        var prompt = SystemPrompt.build(registry, null, null, "/cwd", List.of());
        assertTrue(prompt.contains("Use edit for precise changes"));
    }

    @Test
    void buildIncludesBashGuidelines() {
        var registry = new ToolRegistry();
        registry.register(fakeTool("bash", "Runs a command"));
        var prompt = SystemPrompt.build(registry, null, null, "/cwd", List.of());
        assertTrue(prompt.contains("Commands run in the current working directory"));
    }

    @Test
    void buildIncludesGlobGuidelines() {
        var registry = new ToolRegistry();
        registry.register(fakeTool("glob", "Finds files"));
        var prompt = SystemPrompt.build(registry, null, null, "/cwd", List.of());
        assertTrue(prompt.contains("Search for files by glob pattern"));
    }

    @Test
    void buildIncludesTranscendenceGuidelines() {
        var registry = new ToolRegistry();
        registry.register(fakeTool("transcendence", "Self-modification"));
        var prompt = SystemPrompt.build(registry, null, null, "/cwd", List.of());
        assertTrue(prompt.contains("Self-modification"));
    }

    @Test
    void unknownToolProducesEmptyGuideline() {
        var registry = new ToolRegistry();
        registry.register(fakeTool("unknown_tool", "some tool"));
        var prompt = SystemPrompt.build(registry, null, null, "/cwd", List.of());
        assertTrue(prompt.contains("- `unknown_tool`: some tool"));
    }

    @Test
    void toolsAreSortedByNameForConsistentOutput() {
        var registry = new ToolRegistry();
        registry.register(fakeTool("write", "writes files"));
        registry.register(fakeTool("read", "reads files"));
        var prompt = SystemPrompt.build(registry, null, null, "/cwd", List.of());
        int readPos = prompt.indexOf("- `read`:");
        int writePos = prompt.indexOf("- `write`:");
        assertTrue(readPos < writePos, "tools should be sorted alphabetically");
    }

    @Test
    void nullAppendPromptSkipped() {
        var registry = new ToolRegistry();
        var prompt = SystemPrompt.build(registry, null, null, "/cwd", List.of());
        assertFalse(prompt.contains("ADDITIONAL INSTRUCTIONS"));
    }

    @Test
    void blankAppendPromptSkipped() {
        var registry = new ToolRegistry();
        var prompt = SystemPrompt.build(registry, null, "", "/cwd", List.of());
        assertFalse(prompt.contains("ADDITIONAL INSTRUCTIONS"));
    }
}
