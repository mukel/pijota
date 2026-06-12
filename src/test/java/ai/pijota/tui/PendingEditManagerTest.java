package ai.pijota.tui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PendingEditManagerTest {

    @Test
    void initialHasNoPath() {
        var pem = new PendingEditManager("/cwd");
        assertFalse(pem.hasPath());
        assertFalse(pem.hasEditResult());
    }

    @Test
    void onToolStartStoresToolInfo() {
        var pem = new PendingEditManager("/cwd");
        pem.onToolStart("edit", () -> "{\"filePath\":\"nonexistent.txt\"}");
        assertEquals("edit", pem.toolName());
        assertNotNull(pem.toolArgs());
    }

    @Test
    void onToolStartWithNullArgsDoesNothing() {
        var pem = new PendingEditManager("/cwd");
        pem.onToolStart("edit", null);
        assertFalse(pem.hasPath());
    }

    @Test
    void onToolStartWithInvalidJsonDoesNotSetPath() {
        var pem = new PendingEditManager("/cwd");
        pem.onToolStart("edit", () -> "not json");
        assertFalse(pem.hasPath());
    }

    @Test
    void onToolStartWithExistingFileCapturesOldText(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.txt");
        Files.writeString(file, "hello world");

        var pem = new PendingEditManager(tmp.toString());
        pem.onToolStart("edit", () -> "{\"filePath\":\"" + file + "\"}");
        assertTrue(pem.hasPath());
        assertEquals("hello world", pem.oldText());
    }

    @Test
    void onToolResultAfterSuccessfulEditClearsPreviewIfDifferent(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.txt");
        Files.writeString(file, "hello world");

        var pem = new PendingEditManager(tmp.toString());
        pem.onToolStart("edit", () -> "{\"filePath\":\"" + file
                                        + "\",\"oldText\":\"hello world\",\"newText\":\"hi world\"}");
        pem.setPreviewShown(true);
        pem.onToolResult(true, false);
        assertNotNull(pem.newText());
    }

    @Test
    void onToolResultWithErrorClearsState(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.txt");
        Files.writeString(file, "content");

        var pem = new PendingEditManager(tmp.toString());
        pem.onToolStart("edit", () -> "{\"filePath\":\"" + file + "\"}");
        pem.onToolResult(false, true);
        assertFalse(pem.hasPath());
        assertNull(pem.oldText());
    }

    @Test
    void clearResetsAllState(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.txt");
        Files.writeString(file, "content");

        var pem = new PendingEditManager(tmp.toString());
        pem.onToolStart("edit", () -> "{\"filePath\":\"" + file + "\"}");
        pem.setPreviewShown(true);
        pem.clear();
        assertFalse(pem.hasPath());
        assertFalse(pem.hasEditResult());
        assertNull(pem.toolName());
        assertNull(pem.toolArgs());
    }

    @Test
    void previewEditWithSingleOldNewText(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.txt");
        Files.writeString(file, "hello world");

        var pem = new PendingEditManager(tmp.toString());
        pem.onToolStart("edit", () -> "{\"filePath\":\"" + file
                                        + "\",\"oldText\":\"hello world\",\"newText\":\"hi world\"}");
        assertEquals("hi world", pem.newText());
    }

    @Test
    void previewEditWithEditsArray(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.txt");
        Files.writeString(file, "before after");

        var pem = new PendingEditManager(tmp.toString());
        pem.onToolStart("edit", () -> "{\"filePath\":\"" + file
                                        + "\",\"edits\":[{\"oldText\":\"before\",\"newText\":\"START\"}]}");
        assertEquals("START after", pem.newText());
    }

    @Test
    void previewEditWithOldStringNewStringAliases(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.txt");
        Files.writeString(file, "old text");

        var pem = new PendingEditManager(tmp.toString());
        pem.onToolStart("edit", () -> "{\"filePath\":\"" + file
                                        + "\",\"oldString\":\"old text\",\"newString\":\"new text\"}");
        assertEquals("new text", pem.newText());
    }

    @Test
    void hasEditResultTrueWhenOldAndNewDiffer(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.txt");
        Files.writeString(file, "alpha beta");

        var pem = new PendingEditManager(tmp.toString());
        pem.onToolStart("edit", () -> "{\"filePath\":\"" + file + "\",\"oldText\":\"alpha\",\"newText\":\"gamma\"}");
        assertTrue(pem.hasEditResult());
    }
}
