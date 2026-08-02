package org.pijota.tui;

import org.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Translates the active theme into fzf's {@code --color} spec, so the {@code @} picker arrives in the session's own
 * palette instead of fzf's defaults. Without it the picker is the one surface that looks like a different program:
 * pijota hands the terminal over mid-sentence and a differently-coloured UI appears over the transcript.
 *
 * <p>
 * Backgrounds are pinned to {@code -1} (the terminal's own) rather than painted. Pijota never fills a background, so a
 * picker that did would sit on the transcript as an opaque slab, and would clash outright with a translucent terminal.
 *
 * <p>
 * Only 24-bit colours are translated. A slot carrying just an attribute (pijota's {@code statusText} is dim with no
 * hue) has no fzf equivalent, so that element is left at the terminal default rather than guessed at.
 */
final class FzfTheme {
    private FzfTheme() {}

    /** {@code ESC[…38;2;R;G;B…m}: the truecolor foreground a slot resolves to, if it has one. */
    private static final Pattern TRUECOLOR = Pattern.compile("38;2;(\\d{1,3});(\\d{1,3});(\\d{1,3})");

    /**
     * The fzf element to theme slot mapping. Order is preserved so the generated spec is stable and diffable, which
     * matters because it ends up inside a shell command a user may well read.
     */
    private static final Map<String, String> ELEMENTS = new LinkedHashMap<>();

    static {
        ELEMENTS.put("fg", "text"); // unselected rows
        ELEMENTS.put("fg+", "heading"); // the row under the cursor, matching the selector's own highlight
        ELEMENTS.put("hl", "prompt"); // the matched substring, in the accent
        ELEMENTS.put("hl+", "prompt"); // ... and the same on the selected row, so it does not jump colour
        ELEMENTS.put("prompt", "prompt"); // the "@ " prompt, matching the ❯ the picker was opened from
        ELEMENTS.put("pointer", "prompt");
        ELEMENTS.put("marker", "prompt");
        ELEMENTS.put("spinner", "prompt");
        ELEMENTS.put("info", "commandMeta"); // the match counter, as dim as the status line's metadata
        ELEMENTS.put("header", "commandMeta");
        ELEMENTS.put("query", "text");
        // Chrome. Left unmapped these keep fzf's own 256-colour defaults, which is exactly the mismatch this class
        // exists to remove: the separator under the counter was the last line still in another program's palette.
        ELEMENTS.put("separator", "commandMeta");
        ELEMENTS.put("scrollbar", "commandMeta");
        ELEMENTS.put("border", "commandMeta");
        ELEMENTS.put("label", "commandMeta");
        ELEMENTS.put("disabled", "commandMeta");
        ELEMENTS.put("gutter", "commandMeta");
    }

    /** fzf's own monochrome preset, for a theme with colour switched off (NO_COLOR, or a dumb terminal). */
    static final String MONOCHROME = "bw";

    /**
     * The value for fzf's {@code --color} flag: never blank, so the caller can always pass the flag.
     */
    static String colorSpec(AnsiTheme theme) {
        if (theme == null || !theme.enabled)
            return MONOCHROME;
        // Backgrounds first, so an element mapping cannot accidentally paint one. The gutter is not a background: it
        // is a ▌ drawn beside every unselected row, and left at the terminal default it reads as a bright stripe down
        // the list. Dim chrome instead, like every other rail in the interface.
        var parts = new ArrayList<String>(List.of("bg:-1", "bg+:-1"));
        for (var entry : ELEMENTS.entrySet()) {
            String hex = hexOf(theme, entry.getValue());
            parts.add(entry.getKey() + ":" + (hex == null ? "-1" : hex));
        }
        return String.join(",", parts);
    }

    /** The {@code #rrggbb} a slot resolves to, or null when it carries no colour of its own. */
    private static String hexOf(AnsiTheme theme, String slot) {
        var matcher = TRUECOLOR.matcher(theme.style(slot));
        if (!matcher.find())
            return null;
        int r = Integer.parseInt(matcher.group(1));
        int g = Integer.parseInt(matcher.group(2));
        int b = Integer.parseInt(matcher.group(3));
        if (r > 255 || g > 255 || b > 255)
            return null;
        return String.format("#%02x%02x%02x", r, g, b);
    }
}
