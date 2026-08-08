package org.pijota.tui;

import org.pijota.agent.TestSession;
import org.pijota.config.AppConfig;
import org.pijota.config.SettingsManager;
import org.pijota.testkit.Await;
import org.pijota.testkit.FauxLlm;
import org.pijota.theme.AnsiTheme;
import org.pijota.tools.SlashCommandTool;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** A Terminal that delegates everything but counts enterRawMode() calls, to observe terminal-mode re-asserts. */
    private static Terminal countingRawMode(Terminal delegate, AtomicInteger counter) {
        return (Terminal) Proxy.newProxyInstance(Terminal.class.getClassLoader(), new Class<?>[] {Terminal.class},
                                        (proxy, method, args) -> {
                                            if (method.getName().equals("enterRawMode"))
                                                counter.incrementAndGet();
                                            return method.invoke(delegate, args);
                                        });
    }

    private AppendOnlyTui tui(TestSession ts) throws Exception {
        return tui(ts, headlessTerminal());
    }

    private AppendOnlyTui tui(TestSession ts, Terminal terminal) throws Exception {
        var settings = new SettingsManager(new AppConfig().withApiKey("openai", "sk-test"), c -> {});
        return new AppendOnlyTui(ts.session(), AnsiTheme.plain(), "openai", "gpt-x", List.of(), settings, List.of(),
                                        List::of, List.of(), terminal);
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
            // confirm/prompt return pending futures (answered by a keypress we never send), just exercise the wiring
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
    void sigcontHandlerReassertsModesOnlyWhileSuspended() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var rawCalls = new AtomicInteger();
        var tui = tui(ts, countingRawMode(headlessTerminal(), rawCalls));
        try {
            tui.installSigcontHandler();

            // not suspended: a stray SIGCONT must not touch the terminal (a tcsetattr from a backgrounded process
            // would re-stop it via SIGTTOU)
            sun.misc.Signal.raise(new sun.misc.Signal("CONT"));
            Thread.sleep(300); // signal delivery is async; give the handler its chance to misbehave
            assertEquals(0, rawCalls.get(), "SIGCONT without a suspend is a no-op");

            // suspended (Ctrl+Z path): fg delivers SIGCONT after the shell's own tcsetattr, so the handler must
            // re-assert raw mode — it is the repair that deterministically lands last in the restore race
            tui.suspendTui();
            sun.misc.Signal.raise(new sun.misc.Signal("CONT"));
            Await.until(() -> rawCalls.get() >= 1, Duration.ofSeconds(5),
                                            "SIGCONT after suspend re-asserts terminal modes");
            tui.resumeTui();
        } finally {
            tui.close();
        }
    }

    @Test
    void resumeNeverClobbersSavedOriginalAttributes() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var tui = tui(ts);
        try {
            var field = AppendOnlyTui.class.getDeclaredField("originalAttributes");
            field.setAccessible(true);
            var marker = new org.jline.terminal.Attributes();
            field.set(tui, marker);
            tui.suspendTui();
            tui.resumeTui();
            assertSame(marker, field.get(tui), "resume re-enters raw mode but keeps the startup attribute snapshot");
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
                // Not refreshModels(): it is not a selector, and it asks every configured provider over the network.
                // On a machine with keys in the environment that is a live API call from `mvn test`, and it wrote a
                // models-cache.json into the developer's own home. Its output is covered by ModelCommandHandlerTest.
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
    void sessionPickerLoadAndForkRunAfterAPersistedTurn() throws Exception {
        var ts = TestSession.in(tempDir).build();
        ts.llm().enqueue(FauxLlm.Response.contentWithUsage("hi"));
        ts.session().prompt("remember this", List.of()).get(5, TimeUnit.SECONDS); // persist a session on disk
        String name = ts.session().sessionName();
        var tui = tui(ts);
        try {
            assertDoesNotThrow(() -> {
                tui.showSessionPicker("");
                tui.loadSession("/load " + name);
                tui.forkSession("/fork " + name);
            });
        } finally {
            tui.close();
        }
    }

    @Test
    void sessionCommandHandlersRunAfterAPersistedTurn() throws Exception {
        var ts = TestSession.in(tempDir).build();
        ts.llm().enqueue(FauxLlm.Response.contentWithUsage("an answer"));
        ts.session().prompt("a question", List.of()).get(5, TimeUnit.SECONDS); // context + a last response to act on
        var tui = tui(ts);
        try {
            assertDoesNotThrow(() -> {
                tui.showContext();
                tui.copyLastResponse();
                tui.exportSession("/export"); // writes into the session's cwd (the temp dir), not the repo root
                tui.saveSession("/save " + tempDir.resolve("my-chat"));
            });
        } finally {
            tui.close();
        }
    }

    @Test
    void askQuestionsRendersAQuestionDialog() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var tui = tui(ts);
        try {
            var q = new SlashCommandTool.Question("Pick one?", "Choice",
                                            List.of(new SlashCommandTool.Option("A", "first"),
                                                                            new SlashCommandTool.Option("B", "second")),
                                            false);
            var future = tui.askQuestions(List.of(q));
            assertNotNull(future, "the question dialog returns a pending answer future");
            assertFalse(future.isDone(), "it awaits the user's selection");
        } finally {
            tui.close();
        }
    }

    @Test
    void usageInfoAndNewSessionRunHeadless() throws Exception {
        var ts = TestSession.in(tempDir).build();
        ts.llm().enqueue(FauxLlm.Response.contentWithUsage("hi"));
        ts.session().prompt("q", List.of()).get(5, TimeUnit.SECONDS);
        var tui = tui(ts);
        try {
            assertDoesNotThrow(() -> {
                tui.showUsage();
                tui.startNewSession();
            });
        } finally {
            tui.close();
        }
    }

    @Test
    void treeRemoteAndUndoRenderHeadless() throws Exception {
        var ts = TestSession.in(tempDir).build();
        var tui = tui(ts);
        try {
            assertDoesNotThrow(() -> {
                tui.showTree();
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
