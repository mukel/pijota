package ai.pijota.extensions.tmux;

import ai.pijota.extensions.ExtensionEvent;
import ai.pijota.extensions.testkit.FakeExtensionAPI;
import ai.pijota.extensions.testkit.FakeExtensionRuntime;
import ai.pijota.extensions.testkit.FakeUiContext;
import ai.pijota.tools.Tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TmuxExtensionTest {

    private static final String SESSION_FILE = "123e4567-1234-1234-1234-123456789012.jsonl";

    @TempDir
    Path tmp;

    private final List<String> killed = new ArrayList<>();
    private final List<String> commands = new ArrayList<>();

    private Path sessionPath() {
        return tmp.resolve(SESSION_FILE);
    }

    /** The sidecar TmuxExtension writes for {@link #SESSION_FILE} (sibling, base name + ".tmux.json"). */
    private Path sidecar() {
        return tmp.resolve("123e4567-1234-1234-1234-123456789012.tmux.json");
    }

    /** Canonical tmux responses (records every command, tracks kills); shared by fakeClient() and fakeClientWith(). */
    private final TmuxClient.CommandRunner canonical = (command, timeout) -> {
        commands.add(String.join(" ", command));
        String first = command.get(0);
        String second = command.size() > 1 ? command.get(1) : "";
        String stdout = "";
        int exit = 0;

        if ("tmux".equals(first) && "-V".equals(second)) {
            stdout = "tmux 3.4";
        } else if ("tmux".equals(first) && "list-panes".equals(second)) {
            stdout = "%0\t1234\tpane-0";
        } else if ("tmux".equals(first) && "capture-pane".equals(second)) {
            stdout = "captured output";
        } else if ("tmux".equals(first) && "list-sessions".equals(second)) {
            stdout = "pijota-123e4567-server\t$0";
        } else if ("tmux".equals(first) && ("kill-session".equals(second) || "kill-pane".equals(second))) {
            killed.add(command.get(command.size() - 1));
        }
        return new TmuxClient.Result(exit, stdout, "");
    };

    private TmuxClient fakeClient() {
        return new TmuxClient(canonical);
    }

    /** The canonical fake, except the given verb (command[1]) returns {@code override}. Collapses one-off stubs. */
    private TmuxClient fakeClientWith(String verb, TmuxClient.Result override) {
        return new TmuxClient((command, timeout) -> {
            if (verb.equals(command.size() > 1 ? command.get(1) : "")) {
                commands.add(String.join(" ", command));
                return override;
            }
            return canonical.run(command, timeout);
        });
    }

    private TmuxClient fakeClientMissing() {
        return new TmuxClient((command, timeout) -> {
            throw new IOException("Cannot run program \"tmux\": error=2, No such file or directory");
        });
    }

    private record Setup(FakeExtensionAPI api, TmuxExtension ext, FakeExtensionRuntime runtime) {
    }

    private FakeExtensionAPI init(TmuxClient client) {
        return initWithExt(client).api();
    }

    /** Construct + init the extension on a fresh runtime (cwd /tmp); fires no session events yet. */
    private Setup buildExt(TmuxClient client) {
        var runtime = new FakeExtensionRuntime();
        runtime.setCwd(Path.of("/tmp"));
        var api = new FakeExtensionAPI(runtime);
        var ext = new TmuxExtension(client);
        ext.init(api);
        return new Setup(api, ext, runtime);
    }

    /** buildExt + the usual SessionStart (sidecar lands in the temp dir) + onReady. */
    private Setup initWithExt(TmuxClient client) {
        var setup = buildExt(client);
        setup.api().fire(new ExtensionEvent.SessionStart(sessionPath()));
        setup.ext().onReady(setup.runtime());
        return setup;
    }

    private Tool.ToolResult runTool(FakeExtensionAPI api, String toolName, Map<String, Object> args) throws Exception {
        return api.tool(toolName).execute().execute("id", new Tool.Args(args), "/tmp").get();
    }

    /** Builds a persisted-state snapshot the way saveState() does, for seeding reload/render tests. */
    private static String stateJson(TmuxSession... sessions) {
        try {
            return ai.pijota.util.Util.MAPPER.writeValueAsString(List.of(sessions));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- registration ----

    @Test
    void registersToolsAndSlashCommands() {
        var api = init(fakeClient());
        assertNotNull(api.tool("tmux_bash"));
        assertNotNull(api.tool("tmux_send"));
        assertNotNull(api.tool("tmux_capture"));
        assertNotNull(api.tool("tmux_list"));
        assertNotNull(api.tool("tmux_kill"));
        assertNotNull(api.slashCommand("tmux-list"));
        assertNotNull(api.slashCommand("tmux-send"));
        assertNotNull(api.slashCommand("tmux-capture"));
        assertNotNull(api.slashCommand("supervise"));
        assertFalse(api.statusLineSuppliers().isEmpty());
    }

    // ---- tmux_bash ----

    @Test
    void tmuxBashStartsSession() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        assertFalse(result.isError());
        assertTrue(result.content().contains("Started tmux session"));
        assertTrue(result.content().contains("server"));
        assertTrue(commands.stream().anyMatch(
                                        c -> c.startsWith("tmux new-session") && c.contains("pijota-123e4567-server")));
    }

    @Test
    void tmuxBashRequiresCommand() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_bash", Map.of());
        assertTrue(result.isError());
        assertTrue(result.content().contains("command is required"));
    }

    @Test
    void tmuxBashGeneratesNameFromCommand() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_bash", Map.of("command", "/usr/local/bin/npm run dev"));
        assertFalse(result.isError());
        var details = result.details();
        assertNotNull(details.get("name"));
        String name = (String) details.get("name");
        assertTrue(name.startsWith("npm-"), "name was: " + name);
    }

    @Test
    void tmuxBashSanitizesUnsafeNames() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "bad name!@#"));
        assertFalse(result.isError());
        String name = (String) result.details().get("name");
        assertEquals("bad-name", name);
    }

    @Test
    void tmuxBashMakesDuplicateNamesUnique() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "echo 1", "name", "server"));
        var result = runTool(api, "tmux_bash", Map.of("command", "echo 2", "name", "server"));
        assertFalse(result.isError());
        assertEquals("server-2", result.details().get("name"));
    }

    @Test
    void tmuxBashUsesProvidedWorkdir() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "pwd", "workdir", "/home"));
        assertTrue(commands.stream().anyMatch(c -> c.contains("-c /home")), commands::toString);
    }

    @Test
    void tmuxBashReturnsErrorWhenTmuxMissing() throws Exception {
        var api = init(fakeClientMissing());
        var result = runTool(api, "tmux_bash", Map.of("command", "echo hi"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("tmux is not installed"));
    }

    // ---- tmux_send ----

    @Test
    void tmuxSendResolvesFriendlyNameToPane() throws Exception {
        var client = fakeClient();
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        commands.clear();
        var result = runTool(api, "tmux_send", Map.of("target", "server", "keys", "C-c"));
        assertFalse(result.isError());
        assertTrue(commands.stream().anyMatch(c -> c.contains("send-keys -t %0 C-c Enter")), commands::toString);
    }

    @Test
    void tmuxSendUsesRawTargetWhenNotFriendlyName() throws Exception {
        var client = fakeClient();
        var api = init(client);
        commands.clear();
        var result = runTool(api, "tmux_send", Map.of("target", "other-session", "keys", "ls"));
        assertFalse(result.isError());
        assertTrue(commands.stream().anyMatch(c -> c.contains("send-keys -t other-session ls Enter")),
                                        commands::toString);
    }

    @Test
    void tmuxSendRejectsControlCharacters() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_send", Map.of("target", "server", "keys", "hello\nworld"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("control characters"));
    }

    @Test
    void tmuxSendRequiresTargetAndKeys() throws Exception {
        var api = init(fakeClient());
        assertTrue(runTool(api, "tmux_send", Map.of("keys", "x")).isError());
        assertTrue(runTool(api, "tmux_send", Map.of("target", "server")).isError());
    }

    // ---- tmux_capture ----

    @Test
    void tmuxCaptureReturnsOutput() throws Exception {
        var client = fakeClient();
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        var result = runTool(api, "tmux_capture", Map.of("target", "server", "lines", 50));
        assertFalse(result.isError());
        assertEquals("captured output", result.content());
    }

    @Test
    void tmuxCaptureClampsLines() throws Exception {
        var client = fakeClient();
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "server"));
        commands.clear();
        runTool(api, "tmux_capture", Map.of("target", "server", "lines", 100_000));
        assertTrue(commands.stream().anyMatch(c -> c.contains("-S -1000")), commands::toString);
    }

    @Test
    void tmuxCaptureRequiresTarget() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_capture", Map.of("lines", 50));
        assertTrue(result.isError());
    }

    // ---- tmux_list ----

    @Test
    void tmuxListShowsSessions() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        var result = runTool(api, "tmux_list", Map.of());
        assertFalse(result.isError());
        assertTrue(result.content().contains("server"));
    }

    @Test
    void tmuxListPanesRequiresSession() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_list", Map.of("scope", "panes"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("session is required"));
    }

    @Test
    void tmuxListPanesReturnsOutput() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_list", Map.of("scope", "panes", "session", "pijota-123e4567-server"));
        assertFalse(result.isError());
        assertTrue(result.content().contains("%0"));
    }

    @Test
    void tmuxListReturnsErrorWhenTmuxMissing() throws Exception {
        var api = init(fakeClientMissing());
        var result = runTool(api, "tmux_list", Map.of());
        assertTrue(result.isError());
    }

    // ---- tmux_kill ----

    @Test
    void tmuxKillRemovesFriendlySession() throws Exception {
        var client = fakeClient();
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        var result = runTool(api, "tmux_kill", Map.of("target", "server", "confirm", true));
        assertFalse(result.isError());
        assertTrue(killed.contains("pijota-123e4567-server"));
        // killed sessions are pruned from the list, not left lingering as dead entries
        assertFalse(runTool(api, "tmux_list", Map.of()).content().contains("server"));
    }

    @Test
    void killingTheLastSessionRemovesTheSidecar() throws Exception {
        var setup = initWithExt(fakeClient());
        runTool(setup.api(), "tmux_bash", Map.of("command", "echo hi", "name", "server"));
        assertTrue(Files.exists(sidecar()));
        runTool(setup.api(), "tmux_kill", Map.of("target", "server", "confirm", true));
        assertFalse(Files.exists(sidecar()), "the sidecar should be deleted once no sessions remain");
    }

    @Test
    void tmuxKillRejectsUnmanagedTarget() throws Exception {
        var client = fakeClient();
        var api = init(client);
        // a raw target Pijota never started could be the user's own session — refuse it
        var result = runTool(api, "tmux_kill", Map.of("target", "some-other-session", "confirm", true));
        assertTrue(result.isError());
        assertTrue(killed.isEmpty());
    }

    @Test
    void tmuxKillWithoutConfirmCancelsWhenNoUi() throws Exception {
        var client = fakeClient();
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        var result = runTool(api, "tmux_kill", Map.of("target", "server")); // no confirm, no UI → cancelled
        assertFalse(result.isError());
        assertTrue(result.content().contains("Cancelled"));
        assertTrue(killed.isEmpty());
    }

    @Test
    void tmuxKillToleratesAlreadyDeadSession() throws Exception {
        var client = new TmuxClient((command, timeout) -> {
            commands.add(String.join(" ", command));
            String last = command.get(command.size() - 1);
            if (command.contains("kill-session") && "pijota-123e4567-server".equals(last)) {
                return new TmuxClient.Result(1, "", "session not found");
            }
            return new TmuxClient.Result(0, "", "");
        });
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        var result = runTool(api, "tmux_kill", Map.of("target", "server", "confirm", true));
        assertFalse(result.isError());
    }

    @Test
    void livenessProbeIsThrottledAcrossStatusPolls() throws Exception {
        var client = fakeClient();
        var setup = initWithExt(client);
        runTool(setup.api(), "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        commands.clear();
        // the status line polls ~once a second; back-to-back calls must not spawn a has-session probe each time
        for (int i = 0; i < 5; i++)
            setup.ext().statusLines();
        long probes = commands.stream().filter(c -> c.contains("has-session")).count();
        assertEquals(1, probes, "liveness should be cached within the TTL, not re-probed on every status poll");
    }

    @Test
    void tmuxAvailabilityIsProbedOnce() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "echo 1", "name", "a"));
        runTool(api, "tmux_capture", Map.of("target", "a"));
        runTool(api, "tmux_list", Map.of());
        long probes = commands.stream().filter(c -> c.equals("tmux -V")).count();
        assertEquals(1, probes, "tmux availability is fixed per process — it should be memoized, not re-probed");
    }

    // ---- state persistence ----

    @Test
    void statePersistsAcrossReload() throws Exception {
        var client = fakeClient();
        var setup1 = initWithExt(client);
        runTool(setup1.api(), "tmux_bash", Map.of("command", "npm run dev", "name", "server"));

        // a fresh extension on the SAME session path reads the sidecar the first one wrote
        var setup2 = initWithExt(client);
        var result = runTool(setup2.api(), "tmux_list", Map.of());
        assertFalse(result.isError());
        assertTrue(result.content().contains("server"));
    }

    @Test
    void loadToleratesCorruptSidecar() throws Exception {
        Files.writeString(sidecar(), "not json {{{"); // a torn/garbage file must not break startup
        var setup = initWithExt(fakeClient());
        var result = runTool(setup.api(), "tmux_list", Map.of());
        assertFalse(result.isError());
        assertTrue(result.content().contains("No supervised"));
    }

    @Test
    void loadToleratesEmptySidecar() throws Exception {
        Files.writeString(sidecar(), "");
        var setup = initWithExt(fakeClient());
        assertFalse(runTool(setup.api(), "tmux_list", Map.of()).isError());
    }

    @Test
    void loadPrunesSessionsThatNoLongerExist() throws Exception {
        // tmux reports the persisted session as gone (e.g. after a reboot) → it's dropped, not shown as a tombstone
        var client = fakeClientWith("has-session", new TmuxClient.Result(1, "", "session not found"));
        Files.writeString(sidecar(), stateJson(new TmuxSession("id", "pijota-123e4567-dead", "dead", "cmd", "", "", 0,
                                        false, true)));
        var setup = initWithExt(client);
        var result = runTool(setup.api(), "tmux_list", Map.of());
        assertTrue(result.content().contains("No supervised"), result::content);
    }

    @Test
    void statePersistsCommandsWithTabsAndNewlines() throws Exception {
        var client = fakeClient();
        var setup1 = initWithExt(client);
        // a tab/newline in the command would have corrupted a tab-and-line delimited format; JSON escapes it
        runTool(setup1.api(), "tmux_bash", Map.of("command", "echo a\tb\nrun", "name", "weird"));

        var setup2 = initWithExt(client); // reload from the sidecar
        var result = runTool(setup2.api(), "tmux_list", Map.of());
        assertFalse(result.isError());
        assertTrue(result.content().contains("weird"), result::content); // the session survived intact
        assertTrue(result.content().contains("echo a"), result::content); // the command was preserved
    }

    // ---- status lines ----

    @Test
    void statusLineEmptyWhenNoSessions() {
        var api = init(fakeClient());
        var lines = api.statusLineSuppliers().getFirst().get();
        assertTrue(lines.isEmpty());
    }

    @Test
    void statusLineShowsActiveSession() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        var lines = api.statusLineSuppliers().getFirst().get();
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("server"));
        assertTrue(lines.getFirst().contains("1 tmux"));
    }

    @Test
    void statusLineTruncatesManySessions() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "echo 1", "name", "one"));
        runTool(api, "tmux_bash", Map.of("command", "echo 2", "name", "two"));
        runTool(api, "tmux_bash", Map.of("command", "echo 3", "name", "three"));
        runTool(api, "tmux_bash", Map.of("command", "echo 4", "name", "four"));
        var lines = api.statusLineSuppliers().getFirst().get();
        assertTrue(lines.getFirst().contains("+1"));
    }

    // ---- shutdown ----

    @Test
    void shutdownKillsAutoCleanupSessions() throws Exception {
        var client = fakeClient();
        var setup = initWithExt(client);
        runTool(setup.api(), "tmux_bash", Map.of("command", "echo hi", "name", "tmp", "cleanup_on_exit", true));
        setup.ext().onShutdown(setup.api().runtime());
        assertTrue(killed.contains("pijota-123e4567-tmp"));
    }

    @Test
    void shutdownKeepsNonAutoCleanupSessions() throws Exception {
        var client = fakeClient();
        var setup = initWithExt(client);
        runTool(setup.api(), "tmux_bash", Map.of("command", "echo hi", "name", "keeper"));
        setup.ext().onShutdown(setup.api().runtime());
        assertFalse(killed.contains("pijota-123e4567-keeper"));
    }

    // ---- slash commands ----

    @Test
    void slashSuperviseRunsCommand() {
        var api = init(fakeClient());
        api.slashCommand("supervise").handler().accept("npm run dev");
        assertTrue(api.lastSentMessage().contains("Started tmux session")
                                        || api.lastSentMessage().contains("npm run dev"));
    }

    @Test
    void slashSuperviseShowsUsageWhenEmpty() {
        var api = init(fakeClient());
        api.slashCommand("supervise").handler().accept("   ");
        assertTrue(api.lastSentMessage().contains("Usage"));
    }

    @Test
    void slashSendShowsUsageWhenEmpty() {
        var api = init(fakeClient());
        api.slashCommand("tmux-send").handler().accept("");
        assertTrue(api.lastSentMessage().contains("Usage"));
    }

    @Test
    void slashSendShowsUsageWhenMissingKeys() {
        var api = init(fakeClient());
        api.slashCommand("tmux-send").handler().accept("server");
        assertTrue(api.lastSentMessage().contains("Usage"));
    }

    @Test
    void slashCaptureShowsUsageWhenEmpty() {
        var api = init(fakeClient());
        api.slashCommand("tmux-capture").handler().accept("  ");
        assertTrue(api.lastSentMessage().contains("Usage"));
    }

    @Test
    void slashCommandsUseCustomNoteType() {
        var api = init(fakeClient());
        api.slashCommand("supervise").handler().accept("echo hi");
        var note = api.sentMessages().getLast();
        assertTrue(note.customType().startsWith("tmux"));
    }

    // ---- session prefix ----

    @Test
    void sessionPrefixDerivedFromSessionFile() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "server"));
        assertTrue(commands.stream().anyMatch(c -> c.contains("pijota-123e4567-server")), commands::toString);
    }

    @Test
    void sessionPrefixFallsBackToRandomWhenPathIsNull() throws Exception {
        var setup = buildExt(fakeClient());
        setup.api().fire(new ExtensionEvent.SessionStart(null));
        setup.ext().onReady(setup.runtime());
        var result = runTool(setup.api(), "tmux_bash", Map.of("command", "echo hi"));
        assertFalse(result.isError());
        String tmuxName = (String) result.details().get("tmux_name");
        assertNotNull(tmuxName);
        assertTrue(tmuxName.startsWith("pijota-"));
    }

    // ---- missing tmux across all tools ----

    @Test
    void allToolsReportMissingTmux() throws Exception {
        var api = init(fakeClientMissing());
        assertTrue(runTool(api, "tmux_bash", Map.of("command", "x")).isError());
        assertTrue(runTool(api, "tmux_send", Map.of("target", "s", "keys", "x")).isError());
        assertTrue(runTool(api, "tmux_capture", Map.of("target", "s")).isError());
        assertTrue(runTool(api, "tmux_list", Map.of()).isError());
        assertTrue(runTool(api, "tmux_kill", Map.of("target", "s")).isError());
    }

    // ---- additional coverage ----

    @Test
    void tmuxBashReportsNewSessionFailure() throws Exception {
        var client = fakeClientWith("new-session", new TmuxClient.Result(1, "", "session exists"));
        var api = init(client);
        var result = runTool(api, "tmux_bash", Map.of("command", "echo hi"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("tmux new-session failed"));
    }

    @Test
    void tmuxBashDefaultsWorkdirToRuntimeCwd() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "echo hi"));
        assertTrue(commands.stream().anyMatch(c -> c.startsWith("tmux new-session") && c.contains("-c /tmp")),
                                        commands::toString);
    }

    @Test
    void tmuxBashSanitizesBlankNameToSession() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "!!!"));
        assertFalse(result.isError());
        assertEquals("session", result.details().get("name"));
    }

    @Test
    void tmuxSendResolvesFriendlyNameToSessionWhenPaneMissing() throws Exception {
        var client = fakeClientWith("list-panes", new TmuxClient.Result(0, "", "")); // no pane → resolve to session
                                                                                     // name
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "server"));
        commands.clear();
        var result = runTool(api, "tmux_send", Map.of("target", "server", "keys", "x"));
        assertFalse(result.isError());
        assertTrue(commands.stream().anyMatch(c -> c.contains("send-keys -t pijota-123e4567-server x Enter")),
                                        commands::toString);
    }

    @Test
    void tmuxSendHonorsEnterFalse() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "server"));
        commands.clear();
        var result = runTool(api, "tmux_send", Map.of("target", "server", "keys", "x", "enter", false));
        assertFalse(result.isError());
        assertEquals("tmux send-keys -t %0 x", commands.getLast());
    }

    @Test
    void tmuxSendReportsSendKeysFailure() throws Exception {
        var client = fakeClientWith("send-keys", new TmuxClient.Result(1, "", "target not found"));
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "server"));
        var result = runTool(api, "tmux_send", Map.of("target", "server", "keys", "x"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("send-keys failed"));
    }

    @Test
    void tmuxCaptureResolvesFriendlyNameToPaneId() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "server"));
        commands.clear();
        var result = runTool(api, "tmux_capture", Map.of("target", "server"));
        assertFalse(result.isError());
        assertTrue(commands.stream().anyMatch(c -> c.contains("capture-pane -t %0")), commands::toString);
    }

    @Test
    void tmuxCaptureReturnsNoOutputForBlankPane() throws Exception {
        var client = fakeClientWith("capture-pane", new TmuxClient.Result(0, "", ""));
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "server"));
        var result = runTool(api, "tmux_capture", Map.of("target", "server"));
        assertFalse(result.isError());
        assertEquals("(no output)", result.content());
    }

    @Test
    void tmuxCaptureReportsCapturePaneFailure() throws Exception {
        var client = fakeClientWith("capture-pane", new TmuxClient.Result(1, "", "no such pane"));
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "server"));
        var result = runTool(api, "tmux_capture", Map.of("target", "server"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("capture-pane failed"));
    }

    @Test
    void tmuxCaptureDefaultLinesIs100() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "server"));
        commands.clear();
        runTool(api, "tmux_capture", Map.of("target", "server"));
        assertTrue(commands.stream().anyMatch(c -> c.contains("-S -100")), commands::toString);
    }

    @Test
    void tmuxListPanesReportsFailure() throws Exception {
        var client = fakeClientWith("list-panes", new TmuxClient.Result(1, "", "no such session"));
        var api = init(client);
        var result = runTool(api, "tmux_list", Map.of("scope", "panes", "session", "pijota-123e4567-server"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("list-panes failed"));
    }

    @Test
    void tmuxListSessionsEmpty() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_list", Map.of());
        assertFalse(result.isError());
        assertTrue(result.content().contains("No supervised"));
    }

    @Test
    void tmuxKillWithUiConfirmation() throws Exception {
        var setup = initWithExt(fakeClient());
        var ui = new FakeUiContext();
        ui.setConfirmResult(true);
        setup.runtime().setUi(ui);
        runTool(setup.api(), "tmux_bash", Map.of("command", "echo hi", "name", "server"));
        var result = runTool(setup.api(), "tmux_kill", Map.of("target", "server")); // no explicit confirm → prompts
        assertFalse(result.isError());
        assertTrue(killed.contains("pijota-123e4567-server"));
    }

    @Test
    void sessionPrefixHandlesFilenameWithoutExtensionAndShortNames() throws Exception {
        var setup = buildExt(fakeClient());
        setup.api().fire(new ExtensionEvent.SessionStart(Path.of("/sessions/my-session")));
        setup.ext().onReady(setup.runtime());
        runTool(setup.api(), "tmux_bash", Map.of("command", "echo hi"));
        assertTrue(commands.stream().anyMatch(c -> c.contains("pijota-my-sessi-")), commands::toString);

        commands.clear();
        setup.api().fire(new ExtensionEvent.SessionStart(Path.of("/sessions/ab")));
        runTool(setup.api(), "tmux_bash", Map.of("command", "echo hi"));
        assertTrue(commands.stream().anyMatch(c -> c.contains("pijota-ab-")), commands::toString);
    }

    @Test
    void slashSendExecutesTool() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "server"));
        commands.clear();
        api.slashCommand("tmux-send").handler().accept("server ls");
        assertTrue(commands.stream().anyMatch(c -> c.contains("send-keys -t %0 ls Enter")), commands::toString);
    }

    @Test
    void slashCaptureExecutesTool() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "server"));
        api.slashCommand("tmux-capture").handler().accept("server");
        assertTrue(api.lastSentMessage().contains("captured output"));
    }
}
