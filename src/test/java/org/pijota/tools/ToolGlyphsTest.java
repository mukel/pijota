package org.pijota.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The glyph vocabulary is a design language, not decoration: the tool's name is printed right after the mark, so the
 * mark carries the effect instead. A new tool picking a mark that overstates what it does (an inspection shown as a
 * write) is a lie a reader cannot check, so the set is closed and enforced here.
 */
class ToolGlyphsTest {

    private static final Set<String> LANGUAGE = Set.of(ToolGlyphs.INSPECT, ToolGlyphs.MUTATE, ToolGlyphs.RUN,
                                    ToolGlyphs.NETWORK, ToolGlyphs.AGENT);

    @Test
    void theMarksAreDistinctSoTheyCanBeToldApartAtAGlance() {
        assertEquals(5, LANGUAGE.size(), "two categories sharing a mark would make the language useless");
    }

    /**
     * Every mark is one code point outside the emoji planes. The emoji these replaced were double-width, which is the
     * one property that can shift a whole column on a terminal that disagrees about a glyph's size, and they were also
     * the only element in the interface that ignored the theme.
     */
    @Test
    void everyMarkIsOneNonEmojiCodePoint() {
        for (String glyph : LANGUAGE) {
            assertEquals(1, glyph.codePointCount(0, glyph.length()), "not a single code point: [" + glyph + "]");
            int cp = glyph.codePointAt(0);
            assertTrue(cp < 0x1F000, "emoji cannot be themed and are double-width: [" + glyph + "]");
        }
    }

    /** Hollow versus filled is the load-bearing distinction; they must not be the same character. */
    @Test
    void inspectAndMutateAreVisuallyOpposite() {
        assertNotEquals(ToolGlyphs.INSPECT, ToolGlyphs.MUTATE);
        assertEquals("○", ToolGlyphs.INSPECT, "hollow reads as 'looked but did not touch'");
        assertEquals("●", ToolGlyphs.MUTATE, "filled reads as 'wrote something'");
    }

    /** The built-in tools must each classify themselves, and only within the language. */
    @Test
    void everyBuiltInToolUsesAMarkFromTheLanguage() {
        record Case(Tool tool, String expected) {
        }
        var cases = List.of(new Case(new ReadTool(), ToolGlyphs.INSPECT), new Case(new LsTool(), ToolGlyphs.INSPECT),
                                        new Case(new GlobTool(), ToolGlyphs.INSPECT),
                                        new Case(new GrepTool(), ToolGlyphs.INSPECT),
                                        new Case(new WriteTool(), ToolGlyphs.MUTATE),
                                        new Case(new EditTool(), ToolGlyphs.MUTATE),
                                        new Case(new ApplyPatchTool(), ToolGlyphs.MUTATE),
                                        new Case(new WebsearchTool(), ToolGlyphs.NETWORK),
                                        new Case(new TodoTool(), ToolGlyphs.AGENT));
        for (Case c : cases) {
            assertTrue(LANGUAGE.contains(c.tool().glyph()),
                                            c.tool().name() + " uses a mark outside the language: " + c.tool().glyph());
            assertEquals(c.expected(), c.tool().glyph(), c.tool().name() + " is classified wrong");
        }
    }

    /** A tool that declares nothing must not be shown as having written to disk. */
    @Test
    void theDefaultMarkClaimsNothingAboutDiskWrites() {
        assertNotEquals(ToolGlyphs.MUTATE, new WhoamiTool().glyph());
        assertTrue(LANGUAGE.contains(new WhoamiTool().glyph()));
    }

    /**
     * The whole point of centralising these is that the language is defined in one file. A stray literal elsewhere is
     * how the vocabulary drifts back into a per-tool free-for-all, which is what it was before: eleven emoji, no rule.
     */
    @Test
    void noToolDefinesItsMarkOutsideTheLanguageFile() throws IOException {
        // Only tool marks: the Telegram welcome message is chat copy, not part of this vocabulary.
        var literal = Pattern.compile("glyph\\(\"([^\"]+)\"|glyph\\(\\)[^{]*\\{\\s*return \"([^\"]+)\"");
        var offenders = new TreeSet<String>();
        Path main = Path.of("src/main/java");
        try (var walk = Files.walk(main)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.endsWith("ToolGlyphs.java"))
                    continue;
                var m = literal.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find())
                    offenders.add(main.relativize(file) + ": " + (m.group(1) != null ? m.group(1) : m.group(2)));
            }
        }
        assertTrue(offenders.isEmpty(), "marks must come from ToolGlyphs so the language stays one decision: "
                                        + offenders);
    }
}
