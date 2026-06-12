package ai.pijota.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GrepToolTest {
    @TempDir
    Path tempDir;

    @Test
    void parseMatchParsesRipgrepJsonOutput() {
        var json = "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"src/Main.java\"},"
                                        + "\"line_number\":42,\"lines\":{\"text\":\"hello world\\n\"}}}";

        var match = GrepTool.parseMatch(json);

        assertNotNull(match);
        assertEquals(42, match.line());
        assertEquals("hello world", match.text());
        assertEquals(Path.of("src/Main.java"), match.path());
    }

    @Test
    void parseMatchReturnsNullForNonMatchTypes() {
        assertNull(GrepTool.parseMatch("{\"type\":\"begin\"}"));
        assertNull(GrepTool.parseMatch("not json"));
        assertNull(GrepTool.parseMatch(""));
    }

    @Test
    void truncateLineLeavesShortLinesIntact() {
        var t = GrepTool.truncateLine("short");
        assertFalse(t.truncated());
        assertEquals("short", t.text());
    }

    @Test
    void reportsNoPatternAsError() throws Exception {
        var tool = new GrepTool((p, sp, ic, lit, g, lim, to) -> new GrepTool.SearchResult("", "", 0, false));

        var result = tool.execute("tc1", Map.of("path", tempDir.toString()), tempDir.toString()).get(1,
                                        TimeUnit.SECONDS);

        assertTrue(result.isError());
        assertTrue(result.content().contains("No pattern"));
    }

    @Test
    void reportsEmptyMatchesAsSuccess() throws Exception {
        var tool = new GrepTool((p, sp, ic, lit, g, lim, to) -> new GrepTool.SearchResult("", "", 0, false));

        var result = tool.execute("tc1", Map.of("pattern", "needle", "path", tempDir.toString()), tempDir.toString())
                                        .get(1, TimeUnit.SECONDS);

        assertFalse(result.isError());
        assertEquals("No matches found", result.content());
    }
}
