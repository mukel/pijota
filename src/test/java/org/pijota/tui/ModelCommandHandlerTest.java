package org.pijota.tui;

import org.pijota.agent.PijotaSession;
import org.pijota.config.AppConfig;
import org.pijota.config.SettingsManager;
import org.pijota.llm.ModelInfo;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelCommandHandlerTest {

    private static ModelCommandHandler.Host fakeHost(String provider, String model) {
        return fakeHost(provider, model, null);
    }

    private static ModelCommandHandler.Host fakeHost(String provider, String model, SettingsManager settingsManager) {
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
            public PijotaSession session() {
                return null;
            }

            @Override
            public SettingsManager settings() {
                return settingsManager;
            }
        };
    }

    @Test
    void availableModelsHonorsEnabledModelsAllowlist() {
        var config = new AppConfig().withApiKey("anthropic", "sk-ant").withApiKey("openai", "sk-test").toBuilder()
                                        .enabledModels(List.of("anthropic/*")).build();
        var settings = new SettingsManager(config, _ -> {});
        var handler = new ModelCommandHandler(fakeHost("anthropic", "x", settings));

        var available = handler.availableModels();
        assertFalse(available.isEmpty());
        assertTrue(available.stream().allMatch(m -> m.provider().equals("anthropic")),
                                        "enabled_models 'anthropic/*' restricts the picker to anthropic models");
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

    @Test
    void cycleModelRefusesWhenBusy() {
        var captured = new ArrayList<String>();
        var host = busyHost("openai", "gpt-4", captured);
        var handler = new ModelCommandHandler(host);
        handler.cycleModel(1);
        assertFalse(captured.isEmpty(), "should capture status message");
    }

    @Test
    void showModelSelectorRefusesWhenBusy() {
        var captured = new ArrayList<String>();
        var host = busyHost("openai", "gpt-4", captured);
        var handler = new ModelCommandHandler(host);
        handler.showModelSelector();
        assertTrue(captured.stream().anyMatch(l -> l.contains("unavailable")), "should report unavailable when busy");
    }

    @Test
    void showModelSelectorWithEmptyModelsReportsError() {
        var captured = new ArrayList<String>();
        var host = capturingHost("openai", "gpt-4", captured);
        var handler = new ModelCommandHandler(host);
        handler.showModelSelector(List.of());
        assertTrue(captured.stream().anyMatch(l -> l.contains("no models")), "should report no models for empty list");
        assertTrue(captured.stream().anyMatch(l -> l.contains("/login") && l.contains("/refresh")),
                                        "empty model picker should explain onboarding next steps");
    }

    private static ModelCommandHandler.Host busyHost(String provider, String model, List<String> output) {
        return new ModelCommandHandler.Host() {
            @Override
            public boolean busy() {
                return true;
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
            public void appendPlain(List<String> lines) {
                output.addAll(lines);
            }

            @Override
            public String styled(String slot, String text) {
                return text;
            }

            @Override
            public void redrawPrompt() {}

            @Override
            public void setStatus(String status) {
                output.add(status);
            }

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
            public PijotaSession session() {
                return null;
            }

            @Override
            public SettingsManager settings() {
                return null;
            }
        };
    }

    private static ModelCommandHandler.Host capturingHost(String provider, String model, List<String> output) {
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
            public void appendPlain(List<String> lines) {
                output.addAll(lines);
            }

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
            public PijotaSession session() {
                return null;
            }

            @Override
            public SettingsManager settings() {
                return null;
            }
        };
    }
}
