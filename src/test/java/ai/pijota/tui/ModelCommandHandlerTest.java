package ai.pijota.tui;

import ai.pijota.llm.ModelInfo;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelCommandHandlerTest {

    private static ModelCommandHandler.Host fakeHost(String provider, String model) {
        return new ModelCommandHandler.Host() {
            @Override
            public boolean busy() {
                return false;
            }

            @Override
            public String provider() {
                return provider;
            }

            @Override
            public String model() {
                return model;
            }

            @Override
            public void appendPlain(List<String> lines) {}

            @Override
            public String styled(String slot, String text) {
                return text;
            }

            @Override
            public void redrawPrompt() {}

            @Override
            public void setStatus(String status) {}

            @Override
            public void switchModel(ModelInfo chosen) {}

            @Override
            public TuiSelectors selectors() {
                return null;
            }

            @Override
            public PromptEditor promptEditor() {
                return null;
            }

            @Override
            public ai.pijota.agent.PijotaSession session() {
                return null;
            }

            @Override
            public ai.pijota.config.SettingsManager settings() {
                return null;
            }
        };
    }

    @Test
    void nextModelCyclesForward() {
        var handler = new ModelCommandHandler(fakeHost("openai", "gpt-4"));
        var models = List.of(new ModelInfo("gpt-3", "openai", "GPT-3", 4096),
                                        new ModelInfo("gpt-4", "openai", "GPT-4", 8192),
                                        new ModelInfo("gpt-4o", "openai", "GPT-4o", 128_000));
        var next = handler.nextModel(models, 1);
        assertEquals("gpt-4o", next.id());
    }

    @Test
    void nextModelCyclesBackward() {
        var handler = new ModelCommandHandler(fakeHost("openai", "gpt-4"));
        var models = List.of(new ModelInfo("gpt-3", "openai", "GPT-3", 4096),
                                        new ModelInfo("gpt-4", "openai", "GPT-4", 8192),
                                        new ModelInfo("gpt-4o", "openai", "GPT-4o", 128_000));
        var next = handler.nextModel(models, -1);
        assertEquals("gpt-3", next.id());
    }

    @Test
    void nextModelWrapsAround() {
        var handler = new ModelCommandHandler(fakeHost("openai", "gpt-4o"));
        var models = List.of(new ModelInfo("gpt-3", "openai", "GPT-3", 4096),
                                        new ModelInfo("gpt-4", "openai", "GPT-4", 8192),
                                        new ModelInfo("gpt-4o", "openai", "GPT-4o", 128_000));
        var next = handler.nextModel(models, 1);
        assertEquals("gpt-3", next.id());
    }

    @Test
    void nextModelWrapsAroundBackward() {
        var handler = new ModelCommandHandler(fakeHost("openai", "gpt-3"));
        var models = List.of(new ModelInfo("gpt-3", "openai", "GPT-3", 4096),
                                        new ModelInfo("gpt-4", "openai", "GPT-4", 8192),
                                        new ModelInfo("gpt-4o", "openai", "GPT-4o", 128_000));
        var next = handler.nextModel(models, -1);
        assertEquals("gpt-4o", next.id());
    }

    @Test
    void nextModelReturnsFirstWhenCurrentNotFound() {
        var handler = new ModelCommandHandler(fakeHost("openai", "unknown-model"));
        var models = List.of(new ModelInfo("gpt-3", "openai", "GPT-3", 4096));
        var next = handler.nextModel(models, 1);
        assertEquals("gpt-3", next.id());
    }

    @Test
    void nextModelReturnsNullForEmptyList() {
        var handler = new ModelCommandHandler(fakeHost("openai", "gpt-4"));
        assertNull(handler.nextModel(List.of(), 1));
    }

    @Test
    void nextModelSingleModelReturnsSame() {
        var handler = new ModelCommandHandler(fakeHost("openai", "gpt-4"));
        var models = List.of(new ModelInfo("gpt-4", "openai", "GPT-4", 8192));
        var next = handler.nextModel(models, 1);
        assertEquals("gpt-4", next.id());
    }

    @Test
    void nextModelDifferentProviderModels() {
        var handler = new ModelCommandHandler(fakeHost("anthropic", "claude"));
        var models = List.of(new ModelInfo("gpt-4", "openai", "GPT-4", 8192),
                                        new ModelInfo("claude", "anthropic", "Claude", 200_000),
                                        new ModelInfo("gemini", "google", "Gemini", 1_000_000));
        var next = handler.nextModel(models, 1);
        assertEquals("gemini", next.id());
    }
}
