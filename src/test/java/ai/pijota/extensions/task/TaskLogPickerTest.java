package ai.pijota.extensions.task;

import ai.pijota.extensions.testkit.FakeExtensionAPI;
import ai.pijota.extensions.testkit.FakeExtensionRuntime;
import ai.pijota.extensions.testkit.FakeUiContext;
import ai.pijota.tools.ToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the {@code /logs} picker against the real {@link TaskExtension} discovery path and the opaque
 * {@code UiContext.select} contract. The user is cancelled (choice = -1) so the test never launches the interactive
 * pager that a real confirmation would spawn.
 */
class TaskLogPickerTest {

    private static final Path LOG_DIR = Path.of("/tmp", "pijota-logs");
    private final List<Path> created = new ArrayList<>();

    @AfterEach
    void cleanup() throws IOException {
        for (Path p : created)
            Files.deleteIfExists(p);
    }

    private Path writeLog(String suffix) throws IOException {
        Files.createDirectories(LOG_DIR);
        Path log = LOG_DIR.resolve("sub-" + suffix + ".log");
        Files.writeString(log, "log body for " + suffix + "\n");
        created.add(log);
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
        ui.setSelectChoice(-1); // cancel: no pager is launched
        var fake = init(ui);

        fake.slashCommand("logs").handler().accept("");

        assertTrue(ui.selectTitles().contains("logs"), "selector should be shown for /logs");
        var labels = ui.lastSelectLabels();
        assertTrue(labels.stream().anyMatch(l -> l.contains(a.getFileName().toString())));
        assertTrue(labels.stream().anyMatch(l -> l.contains(b.getFileName().toString())));
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
