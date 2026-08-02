package org.pijota.tui;

import org.pijota.llm.ModelInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CommandPaletteHandlerTest {
    @Test
    void showCommandPaletteReportsBusyState() {
        var host = new FakeHost();
        host.busy = true;
        var handler = new CommandPaletteHandler(host);

        handler.showCommandPalette();

        // Amber, not red: the palette declining to open mid-turn has failed at nothing.
        assertEquals(List.of("command palette is unavailable while a turn is running"), host.lines);
        assertTrue(host.errors.isEmpty(), "a refusal is not routed through the error channel");
        assertTrue(host.shownItems.isEmpty());
        assertEquals(1, host.redraws);
    }

    @Test
    void showCommandPaletteBuildsGroupedItems() {
        var host = new FakeHost();
        host.commands = List.of(new CompletionItem("/help", "Show help"));
        host.models = List.of(new ModelInfo("zeta", "openai", "Zeta", 128_000),
                                        new ModelInfo("alpha", "anthropic", "Alpha", 64_000));
        host.themes = List.of(new ThemeItem("dark", "", true), new ThemeItem("custom", "/tmp/custom.json", false));
        host.sessions = List.of(new SessionItem(Path.of("session-a.jsonl"), "session-a", "desc", 0, false, 0, 0));
        var handler = new CommandPaletteHandler(host);

        handler.showCommandPalette();

        assertEquals(new CompletionItem("/help", "Chat · Show help"), host.shownItems.get(0));
        assertEquals(new CompletionItem("alpha", "[model] anthropic  62K"), host.shownItems.get(1));
        assertEquals(new CompletionItem("zeta", "[model] openai  125K"), host.shownItems.get(2));
        assertEquals(new CompletionItem("dark", "[theme] current"), host.shownItems.get(3));
        assertEquals(new CompletionItem("custom", "[theme] /tmp/custom.json"), host.shownItems.get(4));
        assertTrue(host.shownItems.get(5).description().startsWith("[session] "));
        assertTrue(host.promptCleared);
        assertEquals(1, host.redraws);
    }

    @Test
    void confirmCommandSelectionDispatchesCommand() {
        var host = new FakeHost();
        host.selected = new CompletionItem("/help", "Chat · Show help");
        var handler = new CommandPaletteHandler(host);

        handler.confirmPaletteSelection();

        assertEquals("/help", host.handledCommand);
        assertTrue(host.deactivated);
        assertTrue(host.promptCleared);
        assertEquals(1, host.redraws);
    }

    @Test
    void confirmModelSelectionSwitchesModel() {
        var host = new FakeHost();
        host.models = List.of(new ModelInfo("gpt-test", "openai", "GPT Test", 128_000));
        host.selected = new CompletionItem("gpt-test", "[model] openai  125K");
        var handler = new CommandPaletteHandler(host);

        handler.confirmPaletteSelection();

        assertEquals("gpt-test", host.switchedModel.id());
    }

    @Test
    void confirmMissingModelReportsError() {
        var host = new FakeHost();
        host.selected = new CompletionItem("missing", "[model] openai  125K");
        var handler = new CommandPaletteHandler(host);

        handler.confirmPaletteSelection();

        assertEquals(List.of("model not found: missing"), host.errors);
    }

    @Test
    void confirmThemeAndSessionSelectionsDispatchByName() {
        var host = new FakeHost();
        var handler = new CommandPaletteHandler(host);

        host.selected = new CompletionItem("dark", "[theme] current");
        handler.confirmPaletteSelection();
        host.selected = new CompletionItem("session-a", "[session] 1h ago");
        handler.confirmPaletteSelection();

        assertEquals("dark", host.switchedTheme);
        assertEquals("session-a", host.loadedSession);
    }

    @Test
    void confirmSelectionIgnoresMissingSelection() {
        var host = new FakeHost();
        var handler = new CommandPaletteHandler(host);

        handler.confirmPaletteSelection();

        assertFalse(host.deactivated);
        assertEquals(0, host.redraws);
    }

    private static final class FakeHost implements CommandPaletteHandler.Host {
        boolean busy;
        boolean deactivated;
        boolean promptCleared;
        int redraws;
        String handledCommand;
        ModelInfo switchedModel;
        String switchedTheme;
        String loadedSession;
        CompletionItem selected;
        List<CompletionItem> commands = List.of();
        List<ModelInfo> models = List.of();
        List<ThemeItem> themes = List.of();
        List<SessionItem> sessions = List.of();
        List<CompletionItem> shownItems = List.of();
        final List<String> errors = new ArrayList<>();
        final List<String> lines = new ArrayList<>();

        @Override
        public boolean busy() {
            return busy;
        }

        @Override
        public List<CompletionItem> commands() {
            return commands;
        }

        @Override
        public List<ModelInfo> availableModels() {
            return models;
        }

        @Override
        public Optional<ModelInfo> model(String id) {
            return models.stream().filter(model -> model.id().equals(id)).findFirst();
        }

        @Override
        public List<ThemeItem> themes() {
            return themes;
        }

        @Override
        public List<SessionItem> sessions() {
            return sessions;
        }

        @Override
        public void showPalette(List<CompletionItem> items) {
            shownItems = items;
        }

        @Override
        public Optional<CompletionItem> selectedPaletteItem() {
            return Optional.ofNullable(selected);
        }

        @Override
        public void deactivatePalette() {
            deactivated = true;
        }

        @Override
        public void clearPrompt() {
            promptCleared = true;
        }

        @Override
        public void redrawPrompt() {
            redraws++;
        }

        @Override
        public void appendError(String message) {
            errors.add(message);
        }

        @Override
        public void appendPlain(List<String> appended) {
            lines.addAll(appended);
        }

        @Override
        public void handleCommand(String command) {
            handledCommand = command;
        }

        @Override
        public void switchModel(ModelInfo model) {
            switchedModel = model;
        }

        @Override
        public void switchTheme(String name) {
            switchedTheme = name;
        }

        @Override
        public void loadSession(String name) {
            loadedSession = name;
        }
    }
}
