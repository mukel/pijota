package ai.pijota.tui;

import ai.pijota.llm.ModelInfo;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class CommandPaletteHandler {
    interface Host extends TuiHost {
        List<CompletionItem> commands();

        List<ModelInfo> availableModels();

        Optional<ModelInfo> model(String id);

        List<ThemeItem> themes();

        List<SessionItem> sessions();

        void showPalette(List<CompletionItem> items);

        Optional<CompletionItem> selectedPaletteItem();

        void deactivatePalette();

        void handleCommand(String command);

        void switchModel(ModelInfo model);

        void switchTheme(String name);

        void loadSession(String name);
    }

    private final Host host;

    CommandPaletteHandler(Host host) {
        this.host = host;
    }

    void showCommandPalette() {
        if (host.busy()) {
            host.appendError("command palette is unavailable while a turn is running");
            host.redrawPrompt();
            return;
        }
        host.showPalette(items());
        host.clearPrompt();
        host.redrawPrompt();
    }

    void confirmPaletteSelection() {
        var selected = host.selectedPaletteItem().orElse(null);
        if (selected == null)
            return;
        String name = selected.name();
        String desc = selected.description();
        host.deactivatePalette();
        host.clearPrompt();
        if (desc.startsWith("[cmd] ")) {
            host.handleCommand(name);
        } else if (desc.startsWith("[model] ")) {
            switchModel(name);
        } else if (desc.startsWith("[theme] ")) {
            host.switchTheme(name);
        } else if (desc.startsWith("[session] ")) {
            host.loadSession(name);
        }
        host.redrawPrompt();
    }

    private List<CompletionItem> items() {
        var items = new java.util.ArrayList<CompletionItem>();
        for (var cmd : host.commands()) {
            items.add(new CompletionItem(cmd.name(), "[cmd] " + cmd.description()));
        }
        for (var model : host.availableModels().stream().sorted(Comparator.comparing(ModelInfo::id)).toList()) {
            items.add(new CompletionItem(model.id(), "[model] " + model.provider() + "  "
                                            + (int) (model.contextWindow() / 1024) + "K"));
        }
        for (var theme : host.themes()) {
            items.add(new CompletionItem(theme.name(), "[theme] " + (theme.current() ? "current" : theme.path())));
        }
        for (var session : host.sessions()) {
            items.add(new CompletionItem(session.name(), "[session] " + session.detailText()));
        }
        return items;
    }

    private void switchModel(String name) {
        var model = host.model(name).orElse(null);
        if (model != null)
            host.switchModel(model);
        else
            host.appendError("model not found: " + name);
    }
}
