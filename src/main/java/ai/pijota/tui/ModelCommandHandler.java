package ai.pijota.tui;

import ai.pijota.llm.ModelInfo;
import ai.pijota.llm.ModelRefresher;
import ai.pijota.llm.ModelRegistry;

import java.util.List;

/**
 * Handles model-related commands extracted from AppendOnlyTui.
 */
final class ModelCommandHandler {

    private final Host host;

    interface Host {
        boolean busy();

        String provider();

        String model();

        void appendPlain(List<String> lines);

        String styled(String slot, String text);

        void redrawPrompt();

        void setStatus(String status);

        void switchModel(ModelInfo chosen);

        TuiSelectors selectors();

        PromptEditor promptEditor();

        ai.pijota.agent.PijotaSession session();

        ai.pijota.config.SettingsManager settings();
    }

    ModelCommandHandler(Host host) {
        this.host = host;
    }

    void showModelSelector() {
        if (host.busy()) {
            host.appendPlain(List.of(host.styled("toolError", "/model is unavailable while a turn is running")));
            host.redrawPrompt();
            return;
        }
        var models = availableModels();
        showModelSelector(models);
    }

    void showModelSelector(List<ModelInfo> models) {
        if (host.busy()) {
            host.appendPlain(List.of(host.styled("toolError", "/model is unavailable while a turn is running")));
            host.redrawPrompt();
            return;
        }
        if (models.isEmpty()) {
            host.appendPlain(List.of(host.styled("toolError", "no models found")));
            return;
        }
        var items = models.stream().map(m -> new ModelItem(m, m.id().equals(host.model()))).toList();
        host.selectors().show(TuiSelectors.Slot.MODEL, items);
        host.selectors().<ModelItem>selectFirst(TuiSelectors.Slot.MODEL, i -> i.info().id().equals(host.model()));
        host.promptEditor().setText("");
        host.redrawPrompt();
    }

    void confirmModelSelection() {
        var selected = host.selectors().<ModelItem>selected(TuiSelectors.Slot.MODEL).orElse(null);
        if (selected == null)
            return;
        var chosen = selected.info();
        host.switchModel(chosen);
        host.selectors().deactivate(TuiSelectors.Slot.MODEL);
        host.promptEditor().setText("");
        host.redrawPrompt();
    }

    void refreshModels() {
        if (host.busy()) {
            host.appendPlain(List.of(host.styled("toolError", "/refresh is unavailable while a turn is running")));
            host.redrawPrompt();
            return;
        }
        var result = ModelRefresher.refresh(host.settings());
        host.appendPlain(List.of(host.styled("statusText", "refreshed " + result.modelsFound() + " models from "
                                        + result.providersChecked() + " provider(s)")));
        for (String error : result.errors()) {
            host.appendPlain(List.of(host.styled("toolError", "refresh: " + error)));
        }
        host.redrawPrompt();
    }

    void cycleModel(int direction) {
        if (host.busy()) {
            host.setStatus("Model switch unavailable while busy");
            host.redrawPrompt();
            return;
        }
        var models = availableModels();
        if (models.size() <= 1)
            return;
        var chosen = nextModel(models, direction);
        host.switchModel(chosen);
    }

    List<ModelInfo> availableModels() {
        return ModelRegistry.KNOWN_MODELS.stream().filter(m -> host.settings() == null
                                        || host.settings().resolveApiKey(m.provider()) != null).toList();
    }

    ModelInfo nextModel(List<ModelInfo> models, int direction) {
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).provider().equals(host.provider()) && models.get(i).id().equals(host.model())) {
                int next = (i + direction + models.size()) % models.size();
                return models.get(next);
            }
        }
        return models.isEmpty() ? null : models.getFirst();
    }
}
