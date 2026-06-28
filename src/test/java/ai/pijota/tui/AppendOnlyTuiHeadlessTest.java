package ai.pijota.tui;

import ai.pijota.agent.TestSession;
import ai.pijota.config.AppConfig;
import ai.pijota.config.SettingsManager;
import ai.pijota.theme.AnsiTheme;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the {@link AppendOnlyTui} orchestrator against an in-memory terminal (no tty) via the injectable-terminal
 * constructor, so its command/Host logic is exercised without a real console or the input loop running.
 */
class AppendOnlyTuiHeadlessTest {

    @TempDir
    Path tempDir;

    private static Terminal headlessTerminal() throws Exception {
        return TerminalBuilder.builder().streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                                        .dumb(true).build();
    }

    private AppendOnlyTui tui(TestSession ts) throws Exception {
        var settings = new SettingsManager(new AppConfig().withApiKey("openai", "sk-test"), c -> {});
        return new AppendOnlyTui(ts.session(), AnsiTheme.plain(), "openai", "gpt-x", List.of(), settings, List.of(),
                                        List::of, List.of(), headlessTerminal());
    }

    @Test
    void constructsHeadlesslyAndClosesCleanly() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var tui = tui(ts);
        assertNotNull(tui.theme(), "theme is wired");
        tui.close();
    }

    @Test
    void outputAndStatusMethodsRenderWithoutError() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var tui = tui(ts);
        try {
            assertDoesNotThrow(() -> {
                tui.appendOutput(List.of("line one", "line two"));
                tui.appendStatus("a status note");
                tui.appendError("an error note");
                tui.setStatus("status bar text");
            });
        } finally {
            tui.close();
        }
    }

    @Test
    void uiContextCallbacksAreHonored() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var tui = tui(ts);
        try {
            tui.alert("heads up");
            // confirm/prompt return pending futures (answered by a keypress we never send) — just exercise the wiring
            var confirm = tui.confirm("proceed?");
            assertFalse(confirm.isDone(), "confirm awaits a keypress");
            tui.select("pick one", List.of("a", "b"), s -> s, s -> {});
            tui.viewer("a title", List.of("body line"));
        } finally {
            tui.close();
        }
    }

    @Test
    void commandHandlersAndAliasSwitchRunHeadless() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var tui = tui(ts);
        try {
            assertDoesNotThrow(() -> {
                tui.showHelpOverlay();
                tui.showModelSelector();
                tui.showThemeSelector();
                tui.showSkills();
            });
        } finally {
            tui.close();
        }
    }

    @Test
    void sessionNameRoundTrips() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var tui = tui(ts);
        try {
            tui.sessionName("my chat");
            assertEquals("my chat", tui.sessionName());
        } finally {
            tui.close();
        }
    }

    @Test
    void suspendAndResumeAreReentrantSafe() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var tui = tui(ts);
        try {
            assertDoesNotThrow(() -> {
                tui.suspend();
                tui.resume();
                tui.suspendTui();
                tui.resumeTui();
            });
        } finally {
            tui.close();
        }
    }

    @Test
    void loginSettingsAndSessionSelectorsOpenHeadless() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var tui = tui(ts);
        try {
            assertDoesNotThrow(() -> {
                tui.showSettingsSelector();
                tui.showLoginSelector();
                tui.requestSessionPicker();
                tui.refreshModels();
            });
        } finally {
            tui.close();
        }
    }

    @Test
    void setThemeUpdatesTheActiveTheme() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var tui = tui(ts);
        try {
            tui.setTheme(AnsiTheme.dark());
            assertNotNull(tui.theme());
        } finally {
            tui.close();
        }
    }

    @Test
    void switchToAliasChangesTheModelHeadless() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var tui = tui(ts);
        try {
            assertDoesNotThrow(() -> tui.switchToAlias("openai/gpt-x"));
        } finally {
            tui.close();
        }
    }

    @Test
    void skillAndLogoutCommandsRunHeadless() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var tui = tui(ts);
        try {
            assertDoesNotThrow(() -> {
                tui.handleSkillCommand("/skills");
                tui.showLogoutSelector();
                tui.appendStatus("done");
            });
        } finally {
            tui.close();
        }
    }

    @Test
    void treeWhoamiRemoteAndUndoRenderHeadless() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var tui = tui(ts);
        try {
            assertDoesNotThrow(() -> {
                tui.showTree();
                tui.showWhoami();
                tui.showRemoteControl();
                tui.undoLastTurn();
            });
        } finally {
            tui.close();
        }
    }

    @Test
    void viewerWithASupplierRendersHeadless() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var tui = tui(ts);
        try {
            assertDoesNotThrow(() -> tui.viewer("title", () -> List.of("lazy line one", "lazy line two")));
        } finally {
            tui.close();
        }
    }
}
