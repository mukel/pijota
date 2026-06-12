package ai.pijota.tools;

import java.util.Map;

import org.junit.jupiter.api.Test;

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
    void executeWithoutModelInfoReturnsError() throws Exception {
        var tool = new WhoamiTool();
        var result = tool.execute("call-1", Map.of(), "/cwd").get();
        assertTrue(result.isError());
        assertTrue(result.content().contains("not available"));
    }

    @Test
    void executeWithModelInfoReturnsDetails() throws Exception {
        var oldModels = ai.pijota.llm.ModelRegistry.KNOWN_MODELS;
        try {
            ai.pijota.llm.ModelRegistry.KNOWN_MODELS = java.util.List
                                            .of(new ai.pijota.llm.ModelInfo("gpt-4o", "openai", "GPT-4o", 128_000));

            var tool = new WhoamiTool();
            tool.setModelInfo("openai", "gpt-4o");
            var result = tool.execute("call-1", Map.of(), "/cwd").get();

            assertFalse(result.isError());
            assertTrue(result.content().contains("gpt-4o"));
            assertTrue(result.content().contains("openai"));
            assertTrue(result.content().contains("GPT-4o"));
            assertTrue(result.content().contains("128000"));
        } finally {
            ai.pijota.llm.ModelRegistry.KNOWN_MODELS = oldModels;
        }
    }

    @Test
    void defaultEnabledIsTrue() {
        var tool = new WhoamiTool();
        assertTrue(tool.defaultEnabled());
    }
}
