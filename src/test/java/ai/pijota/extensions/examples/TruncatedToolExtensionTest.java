package ai.pijota.extensions.examples;

import ai.pijota.extensions.ExtensionRuntime.ExecResult;
import ai.pijota.extensions.testkit.FakeExtensionAPI;
import ai.pijota.extensions.testkit.FakeExtensionRuntime;
import ai.pijota.tools.Tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static ai.pijota.testkit.ToolRunner.run;
import static org.junit.jupiter.api.Assertions.*;

class TruncatedToolExtensionTest {

    @Test
    void name() {
        var ext = new TruncatedToolExtension();
        assertEquals("truncated-tool", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new TruncatedToolExtension();
        assertNotNull(ext.description());
    }

    /** Registers the extension's "rg" tool against a runtime whose exec() returns a canned ExecResult. */
    private Tool rgToolWithExec(int exitCode, String stdout, String stderr) {
        var runtime = new FakeExtensionRuntime();
        runtime.setExecStub(cmd -> new ExecResult(exitCode, stdout, stderr));
        var api = new FakeExtensionAPI(runtime);
        new TruncatedToolExtension().init(api);
        return api.tool("rg").toTool();
    }

    @Test
    void missingRipgrepIsAnErrorNotAFalseNoMatches() {
        // exec() returns -1 when the spawn itself failed (ripgrep not installed)
        var tool = rgToolWithExec(-1, "", "Cannot run program \"rg\": error=2, No such file or directory");

        var result = run(tool, Map.of("pattern", "x"));

        assertTrue(result.isError(), "a missing rg must surface as an error, not 'No matches found'");
        assertTrue(result.content().toLowerCase().contains("not found")
                                        || result.content().contains("rg search failed"), result.content());
    }

    @Test
    void exitCodeOneIsReportedAsNoMatches() {
        // ripgrep convention: exit 1 == ran fine, found nothing
        var tool = rgToolWithExec(1, "", "");

        var result = run(tool, Map.of("pattern", "x"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("No matches"), result.content());
    }

    @Test
    void matchesAreReturned() {
        var tool = rgToolWithExec(0, "file.txt:1:the hit\n", "");

        var result = run(tool, Map.of("pattern", "hit"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("the hit"), result.content());
    }

    @Test
    void outputBeyondTheLineCapIsTruncatedWithANotice() {
        var tool = rgToolWithExec(0, "match line\n".repeat(2500), ""); // > 2000-line cap
        // also exercises the glob + path parameters
        var result = run(tool, Map.of("pattern", "match", "glob", "*.java", "path", "src"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("[Truncated: showing"), result.content());
    }

    @Test
    void outputBeyondTheByteCapIsTruncated() {
        var tool = rgToolWithExec(0, ("a".repeat(500) + "\n").repeat(200), ""); // ~100KB, under the line cap
        var result = run(tool, Map.of("pattern", "a"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("[Truncated"), result.content());
    }
}
