package ai.pijota.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QrTerminalTest {

    @Test
    void rendersNonEmptyBlockMatrix() {
        var lines = QrTerminal.render("http://192.168.1.20:8420/?t=abc123");
        assertFalse(lines.isEmpty());
        String all = String.join("\n", lines);
        assertTrue(all.chars().anyMatch(c -> c == '█' || c == '▀' || c == '▄'), "expected QR half-block glyphs");
        int width = lines.getFirst().length();
        assertTrue(lines.stream().allMatch(l -> l.length() == width), "all rows are the same width");
    }

    @Test
    void differentTextProducesDifferentCode() {
        assertNotEquals(QrTerminal.render("alpha"), QrTerminal.render("a completely different, longer payload"));
    }
}
