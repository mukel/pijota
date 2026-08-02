package org.pijota.modes;

import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SyntaxHighlighterTest {
    @Test
    void pythonDecoratorAtSignIsStyledWithoutChangingSourceText() {
        var highlighted = new SyntaxHighlighter("python", AnsiTheme.dark()).highlightLine("@lru_cache(maxsize=None)");

        assertEquals("@lru_cache(maxsize=None)", AnsiStripper.strip(highlighted));
        assertTrue(highlighted.indexOf("\u001B[") < highlighted.indexOf('@'),
                                        "decorator @ should be styled, not emitted as raw default text");
    }

    @Test
    void disabledThemeLeavesCodeUnchanged() {
        var theme = AnsiTheme.dark();
        theme.enabled = false;

        var highlighted = new SyntaxHighlighter("python", theme).highlightLine("return 42");

        assertEquals("return 42", highlighted);
    }
}
