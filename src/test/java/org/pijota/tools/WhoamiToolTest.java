package org.pijota.tools;

import org.pijota.llm.ModelInfo;
import org.pijota.llm.ModelRegistry;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.pijota.testkit.ToolRunner.run;
import static org.junit.jupiter.api.Assertions.*;

class WhoamiToolTest {

    @Test
    void nameIsWhoami() {
        var tool = new WhoamiTool();
        assertEquals("whoami", tool.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var tool = new WhoamiTool();
        assertNotNull(tool.description());
        assertFalse(tool.description().isBlank());
    }

    @Test
    void schemaHasObjectType() {
        var tool = new WhoamiTool();
        var schema = tool.schema();
        assertEquals("object", schema.get("type"));
    }

    @Test
    void executeWithoutModelInfoReturnsError() {
        var tool = new WhoamiTool();
        var result = run(tool, Map.of(), "/cwd");
        assertTrue(result.isError());
        assertTrue(result.content().contains("not available"));
    }

    @Test
    void executeWithModelInfoReturnsDetails() {
        var oldModels = ModelRegistry.KNOWN_MODELS;
        try {
            ModelRegistry.KNOWN_MODELS = List.of(new ModelInfo("gpt-4o", "openai", "GPT-4o", 128_000));

            var tool = new WhoamiTool();
            tool.setModelInfo("openai", "gpt-4o");
            var result = run(tool, Map.of(), "/cwd");

            assertFalse(result.isError());
            assertTrue(result.content().contains("gpt-4o"));
            assertTrue(result.content().contains("openai"));
            assertTrue(result.content().contains("GPT-4o"));
            assertTrue(result.content().contains("128000"));
        } finally {
            ModelRegistry.KNOWN_MODELS = oldModels;
        }
    }

    @Test
    void defaultEnabledIsTrue() {
        var tool = new WhoamiTool();
        assertTrue(tool.defaultEnabled());
    }
}
