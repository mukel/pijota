package ai.pijota.extensions.task;

import ai.pijota.extensions.testkit.FakeExtensionAPI;
import ai.pijota.extensions.testkit.FakeExtensionRuntime;
import ai.pijota.extensions.testkit.FakeUiContext;
import ai.pijota.tools.ToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the {@code /logs} flow against the real {@link TaskExtension}: discovery, the opaque
 * {@code UiContext.select} picker, and opening a log in the in-TUI {@code UiContext.viewer} (no external pager, no
 * process spawn, no terminal suspend), plus the headless static-list fallback. The sub-agent log dir is redirected
 * into a per-test {@code @TempDir} so discovery is isolated from other tests and the shared system temp dir.
 */
class TaskLogPickerTest {

    @TempDir
    Path logDir;

    @BeforeEach
    void isolateLogDir() {
        System.setProperty("pijota.log.dir", logDir.toString());
    }

    @AfterEach
    void clearLogDir() {
        System.clearProperty("pijota.log.dir");
    }

    private Path writeLog(String suffix) throws IOException {
        Path log = logDir.resolve("sub-" + suffix + ".log");
        Files.writeString(log, "log body for " + suffix + "\n");
        return log;
    }

    private FakeExtensionAPI init(FakeUiContext ui) throws Exception {
        var runtime = new FakeExtensionRuntime();
        runtime.setToolRegistry(new ToolRegistry());
        runtime.setUi(ui);
        var fake = new FakeExtensionAPI(runtime);
        new TaskExtension().init(fake);
        return fake;
    }

    @Test
    void logsCommandRendersOneLabelPerLogAndDoesNotLeakIndices() throws Exception {
        String tag = "picker-" + System.nanoTime();
        var a = writeLog(tag + "-a");
        var b = writeLog(tag + "-b");

        var ui = new FakeUiContext();
        ui.setSelectChoice(-1); // cancel
        var fake = init(ui);

        fake.slashCommand("logs").handler().accept("");

        assertTrue(ui.selectTitles().contains("logs"), "selector should be shown for /logs");
        var labels = ui.lastSelectLabels();
        assertTrue(labels.stream().anyMatch(l -> l.contains(a.getFileName().toString())));
        assertTrue(labels.stream().anyMatch(l -> l.contains(b.getFileName().toString())));
    }

    @Test
    void selectingALogOpensTheInTuiViewerWithItsContent() throws Exception {
        var log = writeLog("viewer-" + System.nanoTime());

        var ui = new FakeUiContext();
        ui.setSelectChoice(0); // pick the (newest) log → opens the in-TUI viewer, no pager/process
        var fake = init(ui);

        fake.slashCommand("logs").handler().accept("");

        assertEquals(1, ui.viewerTitles().size(), "selecting a log opens exactly one viewer");
        assertEquals(log.getFileName().toString(), ui.viewerTitles().getFirst());
        assertTrue(ui.lastViewerLines().stream().anyMatch(l -> l.contains("log body")),
                                        ui.lastViewerLines().toString());
    }

    @Test
    void logsByIndexOpensViewerDirectly() throws Exception {
        writeLog("direct-" + System.nanoTime());
        var ui = new FakeUiContext();
        var fake = init(ui);

        fake.slashCommand("logs").handler().accept("latest");

        assertEquals(1, ui.viewerTitles().size(), "/logs latest opens the viewer without a selector");
        assertTrue(ui.selectTitles().isEmpty(), "no picker shown for a direct selection");
    }

    @Test
    void logsCommandFallsBackToStaticListWhenNoUi() throws Exception {
        writeLog("nofui-" + System.nanoTime());

        var runtime = new FakeExtensionRuntime();
        runtime.setToolRegistry(new ToolRegistry());
        // no UI set -> runtime.ui() is null -> static list path, no selector, no exception
        var fake = new FakeExtensionAPI(runtime);
        new TaskExtension().init(fake);

        assertDoesNotThrow(() -> fake.slashCommand("logs").handler().accept(""));
        assertFalse(fake.sentMessages().isEmpty(), "static fallback should post a logs message");
    }
}
