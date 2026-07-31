package org.pijota.tui;

import org.pijota.agent.TestSession;
import org.pijota.config.AppConfig;
import org.pijota.config.SettingsManager;
import org.pijota.messages.ToolCall;
import org.pijota.testkit.FauxLlm;
import org.pijota.theme.AnsiTheme;
import org.pijota.tools.Tool;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style coverage of the live input loop ({@link AppendOnlyTui#run}): a real (stream-backed) terminal is fed
 * keystrokes through a pipe from the test thread while {@code run()} executes on a background thread, exercising key
 * decoding, dispatch, submission and the clean Ctrl+D exit — the part no plain unit test can reach.
 */
class AppendOnlyTuiInputLoopTest {

    @TempDir
    Path tempDir;

    private static final int CTRL_D = 4;
    private static final int ESC = 27;

    /**
     * Drives a TUI session on a background thread: feeds {@code keystrokes}, then ESC (dismiss any modal) and Ctrl+D
     * (clean exit), waits for {@code run()} to return, and gives back everything the TUI wrote to the terminal.
     */
    private String driveSession(TestSession ts, byte[] keystrokes) throws Exception {
        var feeder = new PipedOutputStream();
        var pipedIn = new PipedInputStream(feeder, 8192);
        var captured = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder().streams(pipedIn, captured).build();
        var settings = new SettingsManager(new AppConfig().withApiKey("openai", "sk-test"), c -> {});
        var tui = new AppendOnlyTui(ts.session(), AnsiTheme.plain(), "openai", "gpt-x", List.of(), settings, List.of(),
                                        List::of, List.of(), terminal);

        var finished = new CompletableFuture<Void>();
        var runner = new Thread(() -> {
            try {
                tui.run("9.9.9", tempDir.toString());
                finished.complete(null);
            } catch (Throwable t) {
                finished.completeExceptionally(t);
            }
        }, "tui-run-under-test");
        runner.setDaemon(true);
        runner.start();
        try {
            Thread.sleep(300); // let the greeting render and the input thread spin up
            if (keystrokes.length > 0) {
                feeder.write(keystrokes);
                feeder.flush();
                Thread.sleep(250); // let them be processed (a submitted turn runs on FauxLlm)
            }
            feeder.write(ESC); // dismiss any open overlay/selector so Ctrl+D reaches the exit path
            feeder.flush();
            Thread.sleep(80); // > the 20ms escape-sequence timeout, so ESC is read standalone
            feeder.write(CTRL_D); // Ctrl+D on an empty prompt → clean exit
            feeder.flush();
            finished.get(30, TimeUnit.SECONDS); // run() must return, not hang (generous: a starved CI still isn't a hang)
        } finally {
            tui.close();
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void inputLoopStartsRendersGreetingAndExitsCleanlyOnCtrlD() throws Exception {
        var ts = TestSession.in(tempDir).build();
        String out = driveSession(ts, new byte[0]);
        assertTrue(out.contains("pijota"), "the startup greeting is rendered");
    }

    @Test
    void openOverlaySuppressesGlobalShortcutsSoSelectorsDoNotStack() throws Exception {
        // Seed a resumable session so /sessions has an entry to open a picker over.
        var seed = TestSession.in(tempDir).build();
        seed.llm().enqueue(FauxLlm.Response.contentWithUsage("hi"));
        seed.session().prompt("remember this", List.of()).get(5, TimeUnit.SECONDS);

        var ts = TestSession.in(tempDir).build();
        // "/sessions\r" opens the picker; Ctrl+P (0x10) must NOT open the command palette on top of it. An open
        // overlay is modal, so app shortcuts are suppressed until it is dismissed — otherwise a second selector stacks.
        String keys = "/sessions\r" + (char) 0x10;
        String out = driveSession(ts, keys.getBytes(StandardCharsets.UTF_8));
        assertTrue(out.contains("Enter load"), "the session picker opened (its status hint is shown): " + out);
        assertFalse(out.contains("command palette"),
                                        "Ctrl+P must not open the command palette while the session picker is open");
    }

    @Test
    void exitRestoresTerminalModesSoTheShellIsNotLeftWeird() throws Exception {
        var ts = TestSession.in(tempDir).build();
        String out = driveSession(ts, new byte[0]); // clean Ctrl+D exit runs the terminal restore
        // undo every mode the render loop may have set — a mid-frame exit could otherwise leave autowrap off (broken
        // line-wrap + readline cursor) or bracketed paste on, which reads to the user as "shortcuts don't work".
        assertTrue(out.contains("\033[?7h"), "autowrap must be re-enabled on exit");
        assertTrue(out.contains("\033[?2004l"), "bracketed paste must be disabled on exit");
        assertTrue(out.contains("\033[?25h"), "cursor must be shown on exit");
    }

    @Test
    void typedPromptIsSubmittedAndRunsATurn() throws Exception {
        var ts = TestSession.in(tempDir).build();
        ts.llm().enqueue(FauxLlm.Response.contentWithUsage("hi back"));
        driveSession(ts, "hello\r".getBytes(StandardCharsets.UTF_8)); // type + Enter → submit
        assertFalse(ts.session().userMessages().isEmpty(), "the typed prompt became an agent turn");
    }

    @Test
    void slashCommandIsHandledInLoopWithoutAnAgentTurn() throws Exception {
        var ts = TestSession.in(tempDir).build();
        driveSession(ts, "/help\r".getBytes(StandardCharsets.UTF_8));
        assertTrue(ts.session().userMessages().isEmpty(), "a slash command must not become an agent turn");
    }

    @Test
    void leadingSpaceEscapesSlashCommandIntoAMessage() throws Exception {
        var ts = TestSession.in(tempDir).build();
        ts.llm().enqueue(FauxLlm.Response.contentWithUsage("ok"));
        driveSession(ts, " /help\r".getBytes(StandardCharsets.UTF_8)); // leading space → literal message, not a command
        assertFalse(ts.session().userMessages().isEmpty(),
                                        "a space-prefixed \"/help\" is sent to the model as a message, not run as a command");
    }

    @Test
    void leadingSpaceEscapesVimQuitIntoAMessage() throws Exception {
        var ts = TestSession.in(tempDir).build();
        ts.llm().enqueue(FauxLlm.Response.contentWithUsage("ok"));
        driveSession(ts, " :q\r".getBytes(StandardCharsets.UTF_8)); // leading space → literal message, not a quit
        assertFalse(ts.session().userMessages().isEmpty(),
                                        "a space-prefixed \":q\" is sent to the model as a message, not run as a quit");
    }

    @Test
    void lineEditingKeysAreProcessed() throws Exception {
        var ts = TestSession.in(tempDir).build();
        // type "abcdef", two backspaces (127), Ctrl+U (21) to clear — exercises the editor key paths in the loop
        byte[] keys = {'a', 'b', 'c', 'd', 'e', 'f', 127, 127, 21};
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void controlCAndArrowAndTabKeysAreHandled() throws Exception {
        var ts = TestSession.in(tempDir).build();
        // type text, Ctrl+C (3 → clear), Up arrow (ESC[A → history), Down (ESC[B), Tab (9 → completion)
        byte[] keys = {'h', 'i', 3, ESC, '[', 'A', ESC, '[', 'B', 9};
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void modelSelectorOpensAndNavigatesViaCtrlL() throws Exception {
        var ts = TestSession.in(tempDir).build();
        byte[] keys = {12, ESC, '[', 'B', ESC, '[', 'A'}; // Ctrl+L opens picker, Down, Up
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void commandPaletteOpensViaCtrlP() throws Exception {
        var ts = TestSession.in(tempDir).build();
        byte[] keys = {16, 'm', 'o'}; // Ctrl+P opens palette, type to search
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void overlaysAreCancelableWithEscAndSingleCtrlC() throws Exception {
        // A selector must never trap the user into choosing: Esc or a single Ctrl+C closes it. Inspect overlay state
        // between keystrokes (so a custom harness, not driveSession which auto-sends Esc+Ctrl+D).
        var ts = TestSession.in(tempDir).build();
        var feeder = new PipedOutputStream();
        var pipedIn = new PipedInputStream(feeder, 8192);
        var captured = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder().streams(pipedIn, captured).build();
        var settings = new SettingsManager(new AppConfig().withApiKey("openai", "sk-test"), c -> {});
        var tui = new AppendOnlyTui(ts.session(), AnsiTheme.plain(), "openai", "gpt-x", List.of(), settings, List.of(),
                                        List::of, List.of(), terminal);
        var finished = new CompletableFuture<Void>();
        var runner = new Thread(() -> {
            try {
                tui.run("9.9.9", tempDir.toString());
                finished.complete(null);
            } catch (Throwable t) {
                finished.completeExceptionally(t);
            }
        }, "tui-cancel-test");
        runner.setDaemon(true);
        runner.start();
        try {
            Thread.sleep(300);
            // Ctrl+L opens the model selector (a selectors overlay). Esc closes it.
            feeder.write(12);
            feeder.flush();
            Thread.sleep(150);
            assertTrue(tui.anyOverlayActive(), "Ctrl+L opens the model selector");
            feeder.write(ESC);
            feeder.flush();
            Thread.sleep(150);
            assertFalse(tui.anyOverlayActive(), "Esc cancels the selector");

            // Reopen — a SINGLE Ctrl+C must also cancel it (previously it fell through to interrupt/exit).
            feeder.write(12);
            feeder.flush();
            Thread.sleep(150);
            assertTrue(tui.anyOverlayActive(), "selector reopened");
            feeder.write(3);
            feeder.flush();
            Thread.sleep(150);
            assertFalse(tui.anyOverlayActive(), "a single Ctrl+C cancels the selector");

            feeder.write(CTRL_D);
            feeder.flush();
            finished.get(30, TimeUnit.SECONDS);
        } finally {
            tui.close();
        }
    }

    @Test
    void thinkingToggleAndStatusCollapseShortcuts() throws Exception {
        var ts = TestSession.in(tempDir).build();
        byte[] keys = {ESC, '[', 'Z', 15}; // Shift+Tab (cycle thinking), Ctrl+O (collapse status)
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void historyIsRecalledWithUpAfterASubmit() throws Exception {
        var ts = TestSession.in(tempDir).build();
        ts.llm().enqueue(FauxLlm.Response.contentWithUsage("ok"));
        // submit "first", then Up arrow to recall it from history into the editor
        byte[] keys = {'f', 'i', 'r', 's', 't', '\r', ESC, '[', 'A'};
        assertDoesNotThrow(() -> driveSession(ts, keys));
        assertFalse(ts.session().userMessages().isEmpty());
    }

    @Test
    void bracketedPasteIsAccumulatedIntoTheEditor() throws Exception {
        var ts = TestSession.in(tempDir).build();
        // paste-start (ESC[200~), content, paste-end (ESC[201~)
        byte[] keys = {ESC, '[', '2', '0', '0', '~', 'p', 'a', 's', 't', 'e', 'd', ESC, '[', '2', '0', '1', '~'};
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void emacsStyleEditingKeysAreProcessed() throws Exception {
        var ts = TestSession.in(tempDir).build();
        // type words, then: Ctrl+A (home=1), Ctrl+E (end=5), Ctrl+W (del word=23), Ctrl+K (kill=11), Ctrl+Y (yank=25)
        byte[] keys = {'o', 'n', 'e', ' ', 't', 'w', 'o', 1, 5, 23, 11, 25};
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void aRunningTurnCanBeInterruptedWithDoubleEsc() throws Exception {
        var ts = TestSession.in(tempDir).build();
        ts.llm().blockUntilAborted(); // the turn blocks until aborted
        // submit "go", then double-ESC to force-interrupt the running turn
        byte[] keys = {'g', 'o', '\r', ESC, ESC};
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void modelSelectorFiltersAndSelectsViaKeystrokes() throws Exception {
        var ts = TestSession.in(tempDir).build();
        byte[] keys = {12, 'g', 'p', 't', '\r'}; // Ctrl+L → picker, type "gpt" to filter, Enter to select
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void reverseHistorySearchViaCtrlR() throws Exception {
        var ts = TestSession.in(tempDir).build();
        byte[] keys = {18, 'x', '\r'}; // Ctrl+R reverse-search, type, Enter
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void multiLineInputViaCtrlJ() throws Exception {
        var ts = TestSession.in(tempDir).build();
        byte[] keys = {'a', 10, 'b', 10, 'c'}; // Ctrl+J (newline=10) builds a multi-line buffer
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void historyAndMessageBrowserAfterATurn() throws Exception {
        var ts = TestSession.in(tempDir).build();
        ts.llm().enqueue(FauxLlm.Response.contentWithUsage("answer"));
        // submit a turn, then Up/Up to walk history into the message browser
        byte[] keys = {'h', 'i', '\r', ESC, '[', 'A', ESC, '[', 'A'};
        assertDoesNotThrow(() -> driveSession(ts, keys));
        assertFalse(ts.session().userMessages().isEmpty());
    }

    @Test
    void emacsAndCursorNavigationKeysAreProcessed() throws Exception {
        var ts = TestSession.in(tempDir).build();
        // type text, then Home, Ctrl+right (word fwd), Alt+d (del word fwd), End, Delete, Ctrl+W, Ctrl+left
        byte[] keys = {'h', 'e', 'l', 'l', 'o', ' ', 'w', 'o', 'r', 'l', 'd', ESC, '[', 'H', ESC, 'f', ESC, 'd', ESC,
                '[', 'F', ESC, '[', '3', '~', 23, ESC, 'b'};
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void confirmDialogAcceptedWithYStartsANewConversation() throws Exception {
        var ts = TestSession.in(tempDir).build();
        // "/new" opens a Yes/No confirm; 'y' accepts it
        byte[] keys = {'/', 'n', 'e', 'w', '\r', 'y'};
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void messageDuringARunningTurnIsQueuedAsFollowUp() throws Exception {
        var ts = TestSession.in(tempDir).build();
        ts.llm().blockUntilAborted();
        // submit "first" (turn blocks), then type "second" + Enter → followUp queued, then double-ESC aborts
        byte[] keys = {'f', 'i', 'r', 's', 't', '\r', 's', 'e', 'c', 'o', 'n', 'd', '\r', ESC, ESC};
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void killYankUndoAndRedoKeysAreProcessed() throws Exception {
        var ts = TestSession.in(tempDir).build();
        // type, Ctrl+U (kill line=21), Ctrl+Y (yank=25), Ctrl+_ (undo=31), Alt+_ (redo = ESC _)
        byte[] keys = {'a', 'b', 'c', 'd', 21, 25, 31, ESC, '_'};
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void tabCompletionOnASlashPrefixIsHandled() throws Exception {
        var ts = TestSession.in(tempDir).build();
        byte[] keys = {'/', 'm', 'o', 9}; // type "/mo" then Tab → completion
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void commandPaletteFiltersAndNavigatesThenCancels() throws Exception {
        var ts = TestSession.in(tempDir).build();
        // Ctrl+P palette, type to search, Down/Up, then ESC (driveSession's trailing ESC) cancels
        byte[] keys = {16, 'm', 'o', 'd', ESC, '[', 'B', ESC, '[', 'A'};
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    @Test
    void homeEndDeleteAndPageKeysAreProcessed() throws Exception {
        var ts = TestSession.in(tempDir).build();
        // type, Home, End, Delete, PageUp, PageDown
        byte[] keys = {'x', 'y', 'z', ESC, '[', 'H', ESC, '[', 'F', ESC, '[', '3', '~', ESC, '[', '5', '~', ESC, '[',
                '6', '~'};
        assertDoesNotThrow(() -> driveSession(ts, keys));
    }

    // ─── these drive AppendOnlyTui.onEvent (the agent-event renderer) via real turns of each response shape ───

    @Test
    void streamingDeltasAndFinalAnswerAreRendered() throws Exception {
        var ts = TestSession.in(tempDir).build();
        ts.llm().enqueue(FauxLlm.Response.content("Hello ", "world")); // streamed deltas + a final message
        String out = driveSession(ts, "hi\r".getBytes(StandardCharsets.UTF_8));
        assertTrue(out.contains("world"), "the streamed answer is rendered: " + out);
    }

    @Test
    void thinkingDeltasAreRendered() throws Exception {
        var ts = TestSession.in(tempDir).build();
        ts.llm().enqueue(FauxLlm.Response.thinkingAndContent(List.of("pondering the problem"), "the answer"));
        String out = driveSession(ts, "think\r".getBytes(StandardCharsets.UTF_8));
        assertTrue(out.contains("answer"), out);
    }

    @Test
    void aToolCallAndResultAreRendered() throws Exception {
        var ts = TestSession.in(tempDir).tool(echoTool()).build();
        ts.llm().enqueue(FauxLlm.Response.toolCall(new ToolCall("t1", "echo", "{\"text\":\"hi\"}")))
                                        .enqueue(FauxLlm.Response.contentWithUsage("done"));
        String out = driveSession(ts, "use the tool\r".getBytes(StandardCharsets.UTF_8));
        assertTrue(out.contains("echo") || out.contains("done"), out);
    }

    @Test
    void anAgentErrorIsRendered() throws Exception {
        var ts = TestSession.in(tempDir).build();
        ts.llm().enqueueError(new RuntimeException("boom-401"));
        String out = driveSession(ts, "fail\r".getBytes(StandardCharsets.UTF_8));
        assertTrue(out.contains("boom-401") || out.toLowerCase().contains("error"), out);
    }

    @Test
    void aFailingToolResultIsRendered() throws Exception {
        var failing = new Tool.ToolDefinition("boom", "always fails", Map.of(), (id, a, cwd) -> CompletableFuture
                                        .completedFuture(Tool.ToolResult.error("kaboom"))).toTool();
        var ts = TestSession.in(tempDir).tool(failing).build();
        ts.llm().enqueue(FauxLlm.Response.toolCall(new ToolCall("t1", "boom", "{}")))
                                        .enqueue(FauxLlm.Response.contentWithUsage("recovered"));
        String out = driveSession(ts, "go\r".getBytes(StandardCharsets.UTF_8));
        assertTrue(out.contains("recovered") || out.toLowerCase().contains("boom"), out);
    }

    private static Tool echoTool() {
        return new Tool.ToolDefinition("echo", "Echoes input", Map.of(),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool();
    }

    @Test
    void everySessionSlashCommandRoutesThroughTheController() throws Exception {
        // each routes handleSubmit -> TuiCommandController switch -> the tui handler; driveSession dismisses any
        // overlay (ESC) and exits (Ctrl+D). Covers the command dispatch + the session/render handlers end-to-end.
        // /save writes to an absolute temp path — a bare "/save x" would drop a session file in the repo root.
        // (/export writes "<id>-export.md" into the session's cwd, which TestSession sets to tempDir — no litter.)
        String saveTarget = tempDir.resolve("saved-session.jsonl").toString();
        for (String cmd : List.of("/copy", "/context", "/export", "/save " + saveTarget, "/tree",
                                        "/info", "/undo", "/logs", "/skills", "/settings", "/login", "/logout",
                                        "/remote")) {
            var ts = TestSession.in(tempDir).build();
            assertDoesNotThrow(() -> driveSession(ts, (cmd + "\r").getBytes(StandardCharsets.UTF_8)), cmd);
        }
    }

    @Test
    void slashCommandsDispatchThroughTheLoop() throws Exception {
        // each command routes handleSubmit → command dispatch → its handler (session/model/theme/think pickers, etc.);
        // driveSession appends ESC (dismiss the opened overlay) + Ctrl+D (exit)
        for (String cmd : List.of("/usage", "/sessions", "/model", "/theme", "/think", "/new", "/hotkeys")) {
            var ts = TestSession.in(tempDir).build();
            assertDoesNotThrow(() -> driveSession(ts, (cmd + "\r").getBytes(StandardCharsets.UTF_8)), cmd);
        }
    }
}
