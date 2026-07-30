package org.pijota.extensions.examples;

import org.pijota.agent.PijotaSession;
import org.pijota.agent.SessionDescriptor;
import org.pijota.config.SettingsManager;
import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.UiContext;
import org.pijota.llm.ModelInfo;
import org.pijota.llm.ModelRegistry;
import org.pijota.modes.SlashCommand;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Built-in commands that work on every frontend. Each operates purely on {@link PijotaSession} and the active
 * {@link UiContext} (select/confirm/alert), so it renders natively wherever it runs — a terminal selector, a Telegram
 * inline keyboard, or a web dialog — with no per-UI code. Registered once in {@code AppBootstrap} for all frontends;
 * this is the generic home for feature access that previously had to be re-solved per UI.
 *
 * <p>
 * The TUI currently still intercepts some of these (e.g. {@code /model}) with a richer terminal-native handler; that is
 * a display-state optimization, not a second implementation of the feature — the logic of record lives here.
 */
public final class CoreCommandsExtension implements Extension {

    private static final int MAX_SESSION_CHOICES = 20;

    private final PijotaSession session;
    private final SettingsManager settings;
    private ExtensionAPI api;

    public CoreCommandsExtension(PijotaSession session, SettingsManager settings) {
        this.session = session;
        this.settings = settings;
    }

    @Override
    public String name() {
        return "core-commands";
    }

    @Override
    public String description() {
        return "Built-in commands shared across all frontends";
    }

    @Override
    public void init(ExtensionAPI api) {
        this.api = api;
        api.registerSlashCommand(new SlashCommand("model", "Switch the active model (optionally: /model <query>)",
                                        List.of(), this::cmdModel));
        api.registerSlashCommand(new SlashCommand("new", "Start a new conversation", List.of(), arg -> cmdNew()));
        api.registerSlashCommand(new SlashCommand("undo", "Undo the last user turn", List.of(), arg -> cmdUndo()));
        api.registerSlashCommand(new SlashCommand("sessions", "Resume a previous session", List.of(),
                                        arg -> cmdSessions()));
    }

    private void cmdNew() {
        UiContext ui = api.runtime().ui();
        if (session.isBusy()) {
            ui.alert("/new is unavailable while a turn is running");
            return;
        }
        try {
            session.newSession(); // fires HistoryChanged → frontends re-render the (now empty) transcript
            ui.setStatus("started a new conversation");
        } catch (IOException e) {
            ui.alert("could not start a new session: " + e.getMessage());
        }
    }

    private void cmdUndo() {
        UiContext ui = api.runtime().ui();
        if (session.isBusy()) {
            ui.alert("/undo is unavailable while a turn is running");
            return;
        }
        if (session.undoLastUserTurn().isPresent()) // fires HistoryChanged on success
            ui.setStatus("undid the last turn");
        else
            ui.alert("nothing to undo");
    }

    private void cmdSessions() {
        UiContext ui = api.runtime().ui();
        if (session.isBusy()) {
            ui.alert("/sessions is unavailable while a turn is running");
            return;
        }
        // resumableSessions() is sorted most-recent-first; cap the picker to a manageable, recent slice and label
        // each with the shared SessionDescriptor (name or first message + age) — same labels every frontend shows.
        var recent = session.resumableSessions().stream().limit(MAX_SESSION_CHOICES).toList();
        var sessions = SessionDescriptor.describeResumable(recent); // skip empty/abandoned sessions — nothing to resume
        if (sessions.isEmpty()) {
            ui.alert("no previous sessions to resume");
            return;
        }
        ui.select("Resume session", sessions, SessionDescriptor::label, d -> resume(d.path()));
    }

    private void resume(Path file) {
        UiContext ui = api.runtime().ui();
        try {
            session.load(file); // fires HistoryChanged → frontends re-render the restored transcript
            ui.setStatus("resumed " + file.getFileName());
        } catch (IOException e) {
            ui.alert("could not load session: " + e.getMessage());
        }
    }

    /** {@code /model [query]} — switch the active model via the frontend's native selector (or directly on a match). */
    private void cmdModel(String arg) {
        UiContext ui = api.runtime().ui();
        if (session.isBusy()) {
            ui.alert("/model is unavailable while a turn is running");
            return;
        }
        var models = availableModels();
        if (models.isEmpty()) {
            ui.alert("no models available. Configure an API key first (pijota login)");
            return;
        }
        var matches = arg == null || arg.isBlank() ? models : models.stream().filter(m -> matches(m, arg)).toList();
        if (matches.isEmpty())
            ui.alert("no models match \"" + arg + "\"");
        else if (matches.size() == 1)
            switchTo(matches.getFirst());
        else
            ui.select("Switch model", matches, CoreCommandsExtension::label, this::switchTo);
    }

    private List<ModelInfo> availableModels() {
        return ModelRegistry.availableModels(settings);
    }

    private void switchTo(ModelInfo m) {
        UiContext ui = api.runtime().ui();
        String key = settings != null ? settings.resolveApiKey(m.provider()) : null;
        if (key == null || (key.isBlank() && SettingsManager.requiresApiKey(m.provider()))) {
            ui.alert("no API key for " + m.provider());
            return;
        }
        session.switchModel(m.provider(), m.id(), key, m.contextWindow());
        ui.setStatus("model: " + m.provider() + "/" + m.id());
    }

    private static boolean matches(ModelInfo m, String query) {
        String q = query.toLowerCase();
        return m.id().toLowerCase().contains(q) || m.displayName().toLowerCase().contains(q)
                                        || m.provider().toLowerCase().contains(q);
    }

    private static String label(ModelInfo m) {
        return m.provider() + "/" + m.id() + "  (" + (int) (m.contextWindow() / 1024) + "K)";
    }
}
