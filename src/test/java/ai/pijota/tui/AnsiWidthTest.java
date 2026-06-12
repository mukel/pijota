package ai.pijota.tui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnsiWidthTest {

    @Test
    void visibleWidthPlainAscii() {
        assertEquals(5, AnsiWidth.visibleWidth("hello"));
    }

    @Test
    void visibleWidthEmptyString() {
        assertEquals(0, AnsiWidth.visibleWidth(""));
    }

    @Test
    void visibleWidthStripsAnsiEscapes() {
        var text = "\033[31mred\033[0m";
        assertEquals(3, AnsiWidth.visibleWidth(text));
    }

    @Test
    void visibleWidthAnsiWithMultipleCodes() {
        var text = "\033[1;31mBOLD RED\033[0m";
        assertEquals(8, AnsiWidth.visibleWidth(text));
    }

    @Test
    void visibleWidthTabExpandsToBoundary() {
        assertEquals(4, AnsiWidth.visibleWidth("\t"));
        assertEquals(4, AnsiWidth.visibleWidth("a\t"));
    }

    @Test
    void visibleWidthControlCharsSkipped() {
        assertEquals(1, AnsiWidth.visibleWidth("a\033b"));
    }

    @Test
    void visibleWidthCjkCharacters() {
        var text = "你好";
        assertEquals(4, AnsiWidth.visibleWidth(text));
    }

    @Test
    void visibleWidthEmoji() {
        var text = "🙈";
        assertEquals(2, AnsiWidth.visibleWidth(text));
        assertTrue(AnsiWidth.visibleWidth(text) >= 1);
    }

    @Test
    void visibleWidthZwjSequence() {
        assertEquals(1, AnsiWidth.visibleWidth("\u200D"));
    }

    @Test
    void truncateVisualEmptyWhenMaxZeroOrNegative() {
        assertEquals("", AnsiWidth.truncateVisual("hello", 0));
        assertEquals("", AnsiWidth.truncateVisual("hello", -1));
    }

    @Test
    void truncateVisualKeepsShortText() {
        assertEquals("hello", AnsiWidth.truncateVisual("hello", 10));
    }

    @Test
    void truncateVisualCutsAtWidth() {
        var result = AnsiWidth.truncateVisual("hello world", 5);
        assertTrue(result.startsWith("hello"));
        assertTrue(result.endsWith("\033[0m"));
    }

    @Test
    void wrapPreservingAnsi_emptyForZeroOrNegativeWidth() {
        assertEquals(List.of(""), AnsiWidth.wrapPreservingAnsi("hello", 0));
    }

    @Test
    void wrapPreservingAnsi_singleLineWithinWidth() {
        var lines = AnsiWidth.wrapPreservingAnsi("short text", 20);
        assertEquals(1, lines.size());
        assertEquals("short text", lines.get(0));
    }

    @Test
    void wrapPreservingAnsi_wrapsAtWidth() {
        var lines = AnsiWidth.wrapPreservingAnsi("hello world", 6);
        assertTrue(lines.size() >= 2, "should produce at least 2 lines, got " + lines.size());
    }

    @Test
    void wrapPreservingAnsi_preservesNewlines() {
        var lines = AnsiWidth.wrapPreservingAnsi("line1\nline2", 80);
        assertEquals(2, lines.size());
    }

    @Test
    void wrapPreservingAnsi_handlesAnsiInsideText() {
        var text = "\033[31mred text\033[0m here";
        var lines = AnsiWidth.wrapPreservingAnsi(text, 10);
        assertFalse(lines.isEmpty());
        assertTrue(lines.get(0).contains("\033"));
    }
}
