package org.pijota.tui;

import org.pijota.llm.ModelInfo;
import org.pijota.llm.ModelRegistry;
import org.pijota.modes.SlashCommand;

import java.io.IOException;
import java.util.Comparator;
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
        String arg = SlashCommand.argsOf(input);
        switch (cmd) {
            case "/help" -> tui.showHelpOverlay();
            case "/quit" -> tui.stop();
            case "/model" -> handleModel(arg);
            case "/settings" -> tui.showSettingsSelector();
            case "/login" -> tui.showLoginSelector();
            case "/logout" -> tui.showLogoutSelector();
            case "/refresh" -> tui.refreshModels();
            case "/reload" -> tui.reloadRuntimeResources();
            case "/theme" -> tui.showThemeSelector();
            case "/undo" -> tui.undoLastTurn();
            case "/compact" -> tui.triggerCompaction(arg);
            case "/branch-summary" -> tui.branchSummary(arg);
            case "/copy" -> tui.copyLastResponse();
            case "/hotkeys" -> tui.showHelpOverlay(true);
            case "/session" -> tui.showSessionInfo();
            case "/context" -> tui.showContext();
            case "/usage" -> tui.showUsage();
            case "/dequeue" -> tui.restoreQueuedFollowUp();
            case "/bg" -> tui.backgroundRunningTool(); // tmux-friendly alternative to ctrl+b ctrl+b
            case "/export" -> tui.exportSession(input);
            case "/import" -> tui.importSession(input);
            case "/save" -> tui.saveSession(input);
            case "/load" -> tui.loadSession(input);
            case "/sessions" -> tui.showSessionPicker(arg);
            case "/delete" -> tui.deleteSession(input);
            case "/name" -> renameSession(arg);
            case "/new" -> tui.startNewSession();
            case "/tree" -> tui.showTree(arg);
            case "/fork" -> tui.forkSession(input);
            case "/clone" -> tui.cloneSession();
            case "/remote-control", "/remote" -> tui.showRemoteControl();
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
        // Sanitize before caching locally so the status bar shows the same single-line name that gets persisted — a
        // multi-line/ANSI name must not corrupt the footer (setSessionName sanitizes the persisted copy identically).
        tui.sessionName(org.pijota.agent.PijotaSession.sanitizeName(name));
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
        if (tui.runExtensionCommand(extCmd, input))
            return;
        if (extCmd != null) {
            tui.session().prompt(input);
            return;
        }

        var matches = commands.complete(cmd);
        if (matches.size() == 1) {
            String expanded = commandName(matches.getFirst())
                                            + (input.length() > cmd.length() ? input.substring(cmd.length()) : "");
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

    private static List<ModelInfo> searchModels(String query) {
        String q = query.toLowerCase();
        return ModelRegistry.KNOWN_MODELS.stream().filter(m -> SlashCommandRegistry.fuzzyMatch(q, m.id().toLowerCase())
                                        || SlashCommandRegistry.fuzzyMatch(q, m.displayName().toLowerCase()))
                                        .sorted(Comparator.comparingInt(m -> Math.min(
                                                                        SlashCommandRegistry.matchScore(q, m.id()
                                                                                                        .toLowerCase()),
                                                                        SlashCommandRegistry.matchScore(q, m
                                                                                                        .displayName()
                                                                                                        .toLowerCase()))))
                                        .limit(15).toList();
    }
}
