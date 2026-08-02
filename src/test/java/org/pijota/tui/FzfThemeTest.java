package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class FzfThemeTest {

    private static Map<String, String> parse(String spec) {
        var map = new LinkedHashMap<String, String>();
        for (String part : spec.split(","))
            map.put(part.substring(0, part.indexOf(':')), part.substring(part.indexOf(':') + 1));
        return map;
    }

    /**
     * Pijota never fills a background, so a picker that painted one would sit on the transcript as an opaque slab and
     * would clash outright with a translucent terminal.
     */
    @Test
    void backgroundsAreLeftToTheTerminal() {
        var colors = parse(FzfTheme.colorSpec(AnsiTheme.dark()));
        assertEquals("-1", colors.get("bg"));
        assertEquals("-1", colors.get("bg+"));
    }

    /**
     * fzf's "gutter" is not a background: it is a ▌ drawn beside every unselected row. Left at the terminal default it
     * reads as a bright stripe down the list, louder than anything pijota draws.
     */
    @Test
    void theGutterBarIsDimChromeNotABrightStripe() {
        var theme = AnsiTheme.dark();
        var colors = parse(FzfTheme.colorSpec(theme));
        assertEquals(hex(theme, "commandMeta"), colors.get("gutter"));
        assertNotEquals(colors.get("fg"), colors.get("gutter"), "the rail must recede, not match the row text");
    }

    @Test
    void theAccentIsSharedWithTheRestOfTheInterface() {
        var theme = AnsiTheme.dark();
        var colors = parse(FzfTheme.colorSpec(theme));
        String accent = hex(theme, "prompt");
        assertEquals(accent, colors.get("prompt"), "the picker's prompt matches the ❯ it was opened from");
        assertEquals(accent, colors.get("pointer"));
        assertEquals(accent, colors.get("hl"), "matched text uses the accent, like the selector's own highlight");
        assertEquals(colors.get("hl"), colors.get("hl+"), "the match must not change colour when selected");
    }

    @Test
    void everyValueIsAHexColourOrAnExplicitTerminalDefault() {
        var valid = Pattern.compile("^(#[0-9a-f]{6}|-1)$");
        for (var entry : parse(FzfTheme.colorSpec(AnsiTheme.dark())).entrySet())
            assertTrue(valid.matcher(entry.getValue()).matches(),
                                            entry.getKey() + " is not a colour fzf accepts: " + entry.getValue());
    }

    /**
     * A slot that carries only an attribute (pijota's statusText is dim with no hue) has no fzf equivalent, so the
     * element is left at the terminal default rather than guessed at. It must still be present in the spec.
     */
    @Test
    void aSlotWithNoColourOfItsOwnFallsBackToTheTerminalDefault() {
        var colors = parse(FzfTheme.colorSpec(AnsiTheme.dark()));
        for (String element : new String[] {"fg", "fg+", "info", "header", "query"})
            assertTrue(colors.containsKey(element), element + " must be specified, even as -1: " + colors);
    }

    /**
     * The chrome is what silently stays in fzf's palette when forgotten: the separator under the match counter was the
     * last line in the picker still drawn in another program's colours.
     */
    @Test
    void theChromeIsThemedToo() {
        var colors = parse(FzfTheme.colorSpec(AnsiTheme.dark()));
        for (String element : new String[] {"separator", "scrollbar", "border", "label", "disabled"})
            assertNotNull(colors.get(element), element + " would keep fzf's own default: " + colors);
    }

    @Test
    void colourIsDroppedEntirelyWhenTheThemeIsDisabled() {
        var off = AnsiTheme.dark();
        off.enabled = false;
        assertEquals(FzfTheme.MONOCHROME, FzfTheme.colorSpec(off), "NO_COLOR must reach the picker too");
        assertEquals(FzfTheme.MONOCHROME, FzfTheme.colorSpec(null), "no theme is not a crash");
    }

    @Test
    void lightAndDarkProduceDifferentPickers() {
        assertNotEquals(FzfTheme.colorSpec(AnsiTheme.dark()), FzfTheme.colorSpec(AnsiTheme.light()),
                                        "the picker must follow the theme, not be themed once");
    }

    /** The spec ends up inside a shell command a user may read; a stable order keeps it diffable. */
    @Test
    void theSpecOrderIsStable() {
        assertEquals(FzfTheme.colorSpec(AnsiTheme.dark()), FzfTheme.colorSpec(AnsiTheme.dark()));
        assertEquals(Arrays.asList("bg", "bg+"),
                                        parse(FzfTheme.colorSpec(AnsiTheme.dark())).keySet().stream().limit(2).toList(),
                                        "backgrounds are pinned first");
    }

    /**
     * The themes users actually get are loaded from JSON; AnsiTheme.dark() is only the fallback for an unreadable
     * resource and carries different values, so the shipped ones are checked directly.
     */
    @Test
    void theShippedThemesEachProduceTheirOwnPalette() throws Exception {
        for (String name : new String[] {"dark", "light", "gruvbox-dark"}) {
            var theme = AnsiTheme.loadResource("themes/" + name + ".json");
            var colors = parse(FzfTheme.colorSpec(theme));
            assertEquals(hex(theme, "prompt"), colors.get("prompt"), name + ": the picker prompt must match the ❯");
            assertEquals(hex(theme, "heading"), colors.get("fg+"),
                                            name + ": the selected row must match the selector's own highlight");
            assertEquals("-1", colors.get("bg"), name + ": still no painted background");
        }
    }

    private static String hex(AnsiTheme theme, String slot) {
        var m = Pattern.compile("38;2;(\\d+);(\\d+);(\\d+)").matcher(theme.style(slot));
        assertTrue(m.find(), slot + " has no truecolor to compare against");
        return String.format("#%02x%02x%02x", Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                                        Integer.parseInt(m.group(3)));
    }
}
