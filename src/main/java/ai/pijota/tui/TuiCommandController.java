package ai.pijota.tui;

import ai.pijota.llm.ModelInfo;
import ai.pijota.llm.ModelRegistry;
import ai.pijota.modes.SlashCommand;

import java.io.IOException;
import java.util.List;

final class TuiCommandController {
    private final AppendOnlyTui tui;
    private final SlashCommandRegistry commands;

    TuiCommandController(AppendOnlyTui tui, SlashCommandRegistry commands) {
        this.tui = tui;
        this.commands = commands;
    }

    void handle(String input) {
        String cmd = commandName(input);
        String arg = commandArgs(input, cmd);
        switch (cmd) {
            case "/help" -> tui.showHelpOverlay();
            case "/quit" -> tui.stop();
            case "/model" -> handleModel(arg);
            case "/settings" -> tui.showSettingsSelector();
            case "/login" -> tui.showLoginSelector();
            case "/logout" -> tui.showLogoutSelector();
            case "/refresh" -> tui.refreshModels();
            case "/theme" -> tui.showThemeSelector();
            case "/undo" -> tui.undoLastTurn();
            case "/compact" -> tui.triggerCompaction(arg);
            case "/copy" -> tui.copyLastResponse();
            case "/hotkeys" -> tui.showHelpOverlay(true);
            case "/session" -> tui.showSessionInfo();
            case "/context" -> tui.showContext();
            case "/usage" -> tui.showUsage();
            case "/export" -> tui.exportSession(input);
            case "/save" -> tui.saveSession(input);
            case "/load" -> tui.loadSession(input);
            case "/sessions" -> tui.showSessionPicker(arg);
            case "/delete" -> tui.deleteSession(input);
            case "/name" -> renameSession(arg);
            case "/new" -> tui.startNewSession();
            case "/tree" -> tui.showTree();
            case "/fork" -> tui.forkSession(input);
            case "/clone" -> tui.cloneSession();
            case "/skills" -> tui.showSkills();
            case "/whoami" -> tui.showWhoami();
            default -> handleUnknown(input, cmd);
        }
    }

    private void handleModel(String arg) {
        if (arg.isBlank()) {
            tui.showModelSelector();
            return;
        }
        if (arg.toLowerCase().startsWith(ModelRegistry.ALIAS_PROVIDER + "/")) {
            tui.switchToAlias(arg);
            return;
        }
        var matches = searchModels(arg);
        if (matches.isEmpty())
            tui.appendError("no models match \"" + arg + "\"");
        else
            tui.showModelSelector(matches);
    }

    private void renameSession(String name) {
        tui.sessionName(name.isBlank() ? null : name);
        try {
            tui.session().setSessionName(tui.sessionName());
            tui.appendStatus(tui.sessionName() != null
                                            ? "session named \"" + tui.sessionName() + "\""
                                            : "session name cleared");
        } catch (IOException e) {
            tui.appendError("name failed: " + e.getMessage());
        }
    }

    private void handleUnknown(String input, String cmd) {
        if (cmd.startsWith("/skill:")) {
            tui.handleSkillCommand(input);
            return;
        }

        SlashCommand extCmd = tui.extensionCommand(cmd);
        if (tui.runExtensionCommand(extCmd, input, cmd))
            return;
        if (extCmd != null) {
            tui.session().prompt(input);
            return;
        }

        var matches = commands.complete(cmd);
        if (matches.size() == 1) {
            String expanded = matches.getFirst() + (input.length() > cmd.length() ? input.substring(cmd.length()) : "");
            handle(expanded);
        } else if (!matches.isEmpty()) {
            tui.appendError("ambiguous: " + cmd);
            tui.appendStatus("did you mean " + String.join(", ", matches) + "?");
        } else {
            tui.appendError("unknown: " + input);
            tui.appendStatus("type /help for commands");
        }
    }

    static String commandName(String input) {
        return input.split("\\s+", 2)[0].toLowerCase();
    }

    static String commandArgs(String input, String cmd) {
        return input.length() > cmd.length() ? input.substring(cmd.length()).trim() : "";
    }

    private static List<ModelInfo> searchModels(String query) {
        String q = query.toLowerCase();
        return ModelRegistry.KNOWN_MODELS.stream().filter(m -> SlashCommandRegistry.fuzzyMatch(q, m.id().toLowerCase())
                                        || SlashCommandRegistry.fuzzyMatch(q, m.displayName().toLowerCase()))
                                        .sorted(java.util.Comparator.comparingInt(m -> Math.min(
                                                                        SlashCommandRegistry.matchScore(q, m.id()
                                                                                                        .toLowerCase()),
                                                                        SlashCommandRegistry.matchScore(q, m
                                                                                                        .displayName()
                                                                                                        .toLowerCase()))))
                                        .limit(15).toList();
    }
}
