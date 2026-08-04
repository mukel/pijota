package org.pijota.tui;

import org.pijota.agent.PijotaSession;
import org.pijota.config.SettingsManager;
import org.pijota.llm.ModelInfo;
import org.pijota.llm.ModelRefresher;
import org.pijota.llm.ModelRegistry;
import org.pijota.util.Util;

import java.util.List;

/** Handles model-related commands (selection, cycling, refresh). */
final class ModelCommandHandler {

    private final Host host;

    interface Host extends TuiHost {
        String provider();

        String model();

        void setStatus(String status);

        void switchModel(ModelInfo chosen);

        TuiSelectors selectors();

        PromptEditor promptEditor();

        PijotaSession session();

        SettingsManager settings();
    }

    ModelCommandHandler(Host host) {
        this.host = host;
    }

    void showModelSelector() {
        showModelSelector(availableModels());
    }

    void showModelSelector(List<ModelInfo> models) {
        if (host.refuseWhileBusy("/model")) {
            host.redrawPrompt();
            return;
        }
        if (models.isEmpty()) {
            host.appendPlain(List.of(host.styled("statusText", "no models found"), host.styled("commandMeta",
                                            "run /login, set an API key, or /refresh models")));
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
        if (host.refuseWhileBusy("/refresh")) {
            host.redrawPrompt();
            return;
        }
        var result = ModelRefresher.refresh(host.settings());
        host.appendPlain(List.of(host.styled("statusText", refreshSummary(result))));
        for (String error : result.errors()) {
            host.appendPlain(List.of(host.styled("toolError", "refresh: " + error)));
        }
        host.redrawPrompt();
    }

    /**
     * What the refresh did, in the terms the user asked it in.
     *
     * <p>
     * "refreshed 0 models from 0 provider(s)" was the answer whenever nothing was configured, which names two numbers
     * and no cause. It also never said the result is kept, so the command looked like something to run again every
     * session, which is exactly what it used to be.
     */
    static String refreshSummary(ModelRefresher.Result result) {
        if (result.providersChecked() == 0)
            return "no provider to ask: none has a credential yet";
        String asked = Util.plural(result.providersChecked(), "provider");
        if (result.modelsFound() == 0)
            return "asked " + asked + "; none offered a model list";
        return "found " + Util.plural(result.modelsFound(), "model") + " from " + asked + ", remembered for next time";
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
        return ModelRegistry.availableModels(host.settings());
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
