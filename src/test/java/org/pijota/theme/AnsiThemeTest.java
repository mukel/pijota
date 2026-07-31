package org.pijota.theme;

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
    void thinkingDimsTheBodyAndMarkdownSlotsSoOutputMatchesContentButDimmed() {
        var theme = AnsiTheme.dark();
        var thinking = theme.thinking();
        // The thinking block renders through the same markdown pipeline as content, so every body/markdown slot must be
        // dimmed (differ from the base theme); otherwise prose, bold, code, etc. would show at full brightness.
        for (String slot : new String[] {"text", "bold", "italic", "heading", "codeBody", "synKeyword", "inlineCode"}) {
            assertNotEquals(theme.style(slot), thinking.style(slot), "thinking must dim slot: " + slot);
        }
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

    @Test
    void lightThemeIsNotDark() {
        var light = AnsiTheme.light();
        assertTrue(light.name.contains("light"));
        assertNotEquals(AnsiTheme.dark().text, light.text);
    }

    @Test
    void styledWrapsTextInSlotStyle() {
        var theme = AnsiTheme.dark();
        theme.text = "#ffffff";

        var styled = theme.styled("text", "hello");
        assertTrue(styled.contains("hello"));
        assertTrue(styled.contains("\033["));
    }

    @Test
    void unknownSlotFallsBackToSpecGiven() {
        var theme = AnsiTheme.dark();
        theme.text = "#ffffff";
        var styled = theme.styled("text", "world");
        assertTrue(styled.length() > 5);
    }

    @Test
    void resetReturnsAnsiReset() {
        var theme = AnsiTheme.dark();
        assertEquals("\033[0m", theme.reset());
    }

    @Test
    void thinkingOpacityClampedToValidRange() {
        var t = AnsiTheme.dark();
        t.thinkingOpacity = -0.5;
        var thinking = t.thinking();
        assertNotNull(thinking);

        t.thinkingOpacity = 1.5;
        thinking = t.thinking();
        assertNotNull(thinking);
    }

    @Test
    void lightThemeThinkingUsesLightBackground() {
        var light = AnsiTheme.light();
        light.thinkingOpacity = 0.5;

        var thinking = light.thinking();
        assertNotNull(thinking);
        assertEquals(light.name + "-thinking", thinking.name);
    }

    @Test
    void loadResourceLoadsBuiltinTheme() throws Exception {
        var theme = AnsiTheme.loadResource("themes/catppuccin-mocha.json");
        assertNotNull(theme);
        assertTrue(theme.enabled);
        assertNotNull(theme.name);
        assertFalse(theme.name.isBlank());
    }
}
