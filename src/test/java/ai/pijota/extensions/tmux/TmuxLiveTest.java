package ai.pijota.extensions.tmux;

import ai.pijota.tools.Tool;

import ai.pijota.extensions.ExtensionEvent;
import ai.pijota.extensions.testkit.FakeExtensionAPI;
import ai.pijota.extensions.testkit.FakeExtensionRuntime;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests against a real tmux binary. Opt-in via {@code PIJOTA_TMUX_IT=1}; skips automatically when tmux is
 * not installed.
 */
@Tag("live")
class TmuxLiveTest {

    private static boolean tmuxAvailable() {
        try {
            return new TmuxClient().hasTmux();
        } catch (Exception e) {
            return false;
        }
    }

    private FakeExtensionAPI init() {
        assumeTrue("1".equals(System.getenv("PIJOTA_TMUX_IT")), "set PIJOTA_TMUX_IT=1 to run live tmux tests");
        assumeTrue(tmuxAvailable(), "tmux is not installed");

        var runtime = new FakeExtensionRuntime();
        runtime.setCwd(Path.of(System.getProperty("user.dir")));
        var api = new FakeExtensionAPI(runtime);
        var ext = new TmuxExtension();
        ext.init(api);
        api.fire(new ExtensionEvent.SessionStart(Path.of("/sessions/live-00000000-0000-0000-0000-000000000000.jsonl")));
        api.fire(new ExtensionEvent.SessionReady());
        return api;
    }

    private String tmuxName(FakeExtensionAPI api, String friendlyName) throws Exception {
        var result = api.tool("tmux_bash").execute().execute("id", new Tool.Args(Map.of("command",
                                        "bash -c 'echo hello-from-pijota-tmux; sleep 10'", "name", friendlyName)),
                                        System.getProperty("user.dir")).get(10, TimeUnit.SECONDS);
        assertFalse(result.isError(), result.content());
        var tmuxName = (String) result.details().get("tmux_name");
        assertNotNull(tmuxName);
        return tmuxName;
    }

    @Test
    void bashStartsAndCapturesOutput() throws Exception {
        var api = init();
        String tmuxName = tmuxName(api, "live-echo");

        Thread.sleep(500);

        var capture = api.tool("tmux_capture").execute()
                                        .execute("id", new Tool.Args(Map.of("target", tmuxName)),
                                                                        System.getProperty("user.dir"))
                                        .get(10, TimeUnit.SECONDS);
        assertFalse(capture.isError(), capture.content());
        assertTrue(capture.content().contains("hello-from-pijota-tmux"), capture.content());

        var kill = api.tool("tmux_kill").execute()
                                        .execute("id", new Tool.Args(Map.of("target", "live-echo")),
                                                                        System.getProperty("user.dir"))
                                        .get(10, TimeUnit.SECONDS);
        assertFalse(kill.isError(), kill.content());
    }

    @Test
    void sendKeysCanInterruptProcess() throws Exception {
        var api = init();
        var start = api.tool("tmux_bash").execute()
                                        .execute("id", new Tool.Args(Map.of("command", "sleep 60", "name",
                                                                        "live-sleep")), System.getProperty("user.dir"))
                                        .get(10, TimeUnit.SECONDS);
        assertFalse(start.isError());

        var send = api.tool("tmux_send").execute()
                                        .execute("id", new Tool.Args(Map.of("target", "live-sleep", "keys", "C-c")),
                                                                        System.getProperty("user.dir"))
                                        .get(10, TimeUnit.SECONDS);
        assertFalse(send.isError());

        Thread.sleep(500);

        var list = api.tool("tmux_list").execute()
                                        .execute("id", new Tool.Args(Map.of()), System.getProperty("user.dir"))
                                        .get(10, TimeUnit.SECONDS);
        assertFalse(list.isError());
        assertTrue(list.content().contains("live-sleep"));

        api.tool("tmux_kill").execute().execute("id", new Tool.Args(Map.of("target", "live-sleep")),
                                        System.getProperty("user.dir")).get(10, TimeUnit.SECONDS);
    }

    @Test
    void listPanesReturnsPaneInfo() throws Exception {
        var api = init();
        String tmuxName = tmuxName(api, "live-panes");

        var list = api.tool("tmux_list").execute()
                                        .execute("id", new Tool.Args(Map.of("scope", "panes", "session", tmuxName)),
                                                                        System.getProperty("user.dir"))
                                        .get(10, TimeUnit.SECONDS);
        assertFalse(list.isError(), list.content());
        assertTrue(list.content().startsWith("%"));

        api.tool("tmux_kill").execute().execute("id", new Tool.Args(Map.of("target", "live-panes")),
                                        System.getProperty("user.dir")).get(10, TimeUnit.SECONDS);
    }
}
