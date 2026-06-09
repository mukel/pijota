package ai.pijota.theme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnsiThemeTest {
    @Test
    void disabledThemeReturnsPlainText() {
        var theme = AnsiTheme.dark();
        theme.enabled = false;

        assertEquals("hello", theme.styled("text", "hello"));
        assertEquals("", theme.reset());
    }

    @Test
    void thinkingThemeIsIndependentForSlotsAndExtras() {
        var theme = AnsiTheme.dark();
        theme.text = "#111111";
        theme.extra.put("custom", "red");

        var thinking = theme.thinking();
        thinking.text = "#222222";
        thinking.extra.put("custom", "blue");

        assertEquals("#111111", theme.text);
        assertEquals("red", theme.extra.get("custom"));
        assertEquals("#222222", thinking.text);
        assertEquals("blue", thinking.extra.get("custom"));
    }

    @Test
    void thinkingThemePreservesBaseThemeAndUsesOpacity() {
        var theme = AnsiTheme.dark();
        theme.thinkingText = "#ffffff";
        theme.thinkingOpacity = 0.5;

        var thinking = theme.thinking();

        assertEquals("#ffffff", theme.thinkingText);
        assertNotEquals(theme.thinkingText, thinking.thinkingText);
        assertTrue(thinking.thinkingText.startsWith("#"));
        assertEquals(theme.name + "-thinking", thinking.name);
    }
}
