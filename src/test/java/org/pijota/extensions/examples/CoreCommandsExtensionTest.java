package org.pijota.extensions.examples;

import org.pijota.agent.TestSession;
import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeExtensionRuntime;
import org.pijota.extensions.testkit.FakeUiContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the frontend-agnostic commands through a real {@link org.pijota.agent.PijotaSession} (via {@link TestSession})
 * and a recording {@link FakeUiContext}, asserting observable behaviour (status / alert / selector) rather than
 * internals. Constructed with {@code null} settings so the available-model list is the full builtin catalogue and the
 * tests don't depend on whatever API-key env vars happen to be set.
 */
class CoreCommandsExtensionTest {

    @TempDir
    Path tempDir;

    private record Wired(FakeExtensionAPI api, FakeUiContext ui) {
    }

    private Wired wire() {
        var session = TestSession.in(tempDir).build();
        var ui = new FakeUiContext();
        var runtime = new FakeExtensionRuntime();
        runtime.setUi(ui);
        var api = new FakeExtensionAPI(runtime);
        new CoreCommandsExtension(session.session(), null).init(api);
        return new Wired(api, ui);
    }

    @Test
    void nameAndDescription() {
        var ext = new CoreCommandsExtension(null, null);
        assertEquals("core-commands", ext.name());
        assertFalse(ext.description().isBlank());
    }

    @Test
    void registersEveryFrontendCommand() {
        var w = wire();
        for (String cmd : new String[] {"model", "new", "undo", "compact", "sessions"})
            assertNotNull(w.api().slashCommand(cmd), "missing /" + cmd);
    }

    @Test
    void newStartsAFreshConversation() {
        var w = wire();
        w.api().slashCommand("new").handler().accept("");
        assertTrue(w.ui().statuses().stream().anyMatch(s -> s.contains("started a new conversation")),
                                        w.ui().statuses().toString());
    }

    @Test
    void undoWithNothingToUndoAlerts() {
        var w = wire();
        w.api().slashCommand("undo").handler().accept("");
        assertTrue(w.ui().statuses().stream().anyMatch(s -> s.contains("nothing to undo")),
                                        w.ui().statuses().toString());
    }

    /** The command the web frontend was missing: a small context short-circuits before any LLM call and says so. */
    @Test
    void compactOnASmallContextReportsNothingToCompact() {
        var w = wire();
        w.api().slashCommand("compact").handler().accept("");
        assertTrue(w.ui().statuses().stream().anyMatch(s -> s.contains("nothing to compact")),
                                        w.ui().statuses().toString());
    }

    @Test
    void sessionsWithNoHistoryAlerts() {
        var w = wire();
        w.api().slashCommand("sessions").handler().accept("");
        assertTrue(w.ui().statuses().stream().anyMatch(s -> s.contains("no previous sessions")),
                                        w.ui().statuses().toString());
    }

    @Test
    void modelWithNoMatchAlerts() {
        var w = wire();
        w.api().slashCommand("model").handler().accept("definitely-not-a-real-model-xyz");
        assertTrue(w.ui().statuses().stream().anyMatch(s -> s.contains("no models match")),
                                        w.ui().statuses().toString());
    }

    @Test
    void modelWithoutQueryOffersTheSelector() {
        var w = wire();
        w.api().slashCommand("model").handler().accept("");
        assertTrue(w.ui().selectTitles().contains("Switch model"), w.ui().selectTitles().toString());
        assertFalse(w.ui().lastSelectLabels().isEmpty());
    }
}
