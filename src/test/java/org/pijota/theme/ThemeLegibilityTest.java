package org.pijota.theme;

import org.pijota.llm.ThinkingLevel;
import org.pijota.util.Util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a theme has to get right to be readable, measured rather than eyeballed. Every one of these caught something
 * real when it was written: gruvbox-light shipped a {@code warning} at 3.33:1 on cream, which is the slot whose whole
 * job is catching your eye; {@code light} rendered accent, success and warning at 4.09, 4.07 and 4.07, three meanings
 * at one weight; and three of the four thinking ramps wandered through hues in no order at all.
 */
class ThemeLegibilityTest {

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    /** Slots that carry meaning and must be legible. Decorative ones (dim, the ramp's off step) are exempt. */
    private static final List<String> MUST_READ = List.of("text", "accent", "success", "error", "warning", "muted");

    private record Theme(String name, java.util.Map<String, String> vars, java.util.Map<String, String> colors,
                                    String background) {
        String resolve(String slot) {
            String value = colors.get(slot);
            return value == null ? null : vars.getOrDefault(value, value);
        }
    }

    private static List<Theme> shipped() throws IOException {
        var out = new ArrayList<Theme>();
        try (var files = Files.list(Path.of("src/main/resources/themes"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                var json = Util.MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
                var vars = new java.util.LinkedHashMap<String, String>();
                json.path("vars").fields().forEachRemaining(e -> vars.put(e.getKey(), e.getValue().asText()));
                var colors = new java.util.LinkedHashMap<String, String>();
                json.path("colors").fields().forEachRemaining(e -> colors.put(e.getKey(), e.getValue().asText()));
                out.add(new Theme(json.path("name").asText(), vars, colors,
                                                json.path("export").path("pageBg").asText()));
            }
        }
        assertFalse(out.isEmpty(), "no themes found; these checks would be vacuous");
        return out;
    }

    // ---- colour maths ---------------------------------------------------------------------------------------------

    private static double channel(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double luminance(String hex) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
    }

    /** WCAG contrast ratio, 1.0 (invisible) to 21.0 (black on white). */
    static double contrast(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    static double saturation(String hex) {
        double r = Integer.parseInt(hex.substring(1, 3), 16) / 255.0;
        double g = Integer.parseInt(hex.substring(3, 5), 16) / 255.0;
        double b = Integer.parseInt(hex.substring(5, 7), 16) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max == min)
            return 0;
        double l = (max + min) / 2;
        return l > 0.5 ? (max - min) / (2 - max - min) : (max - min) / (max + min);
    }

    // ---- the checks -----------------------------------------------------------------------------------------------

    /** 4.5:1 is WCAG AA for body text. A slot that means something has to be readable to mean it. */
    @Test
    void everyMeaningfulSlotIsLegibleAgainstItsBackground() throws IOException {
        var failures = new ArrayList<String>();
        for (Theme theme : shipped())
            for (String slot : MUST_READ) {
                String colour = theme.resolve(slot);
                if (colour == null || !HEX.matcher(colour).matches())
                    continue;
                double ratio = contrast(colour, theme.background());
                if (ratio < 4.5)
                    failures.add("%s.%s %s is %.2f:1 on %s".formatted(theme.name(), slot, colour, ratio,
                                                    theme.background()));
            }

        assertTrue(failures.isEmpty(), "below WCAG AA (4.5:1):\n  " + String.join("\n  ", failures));
    }

    /**
     * Semantics have to be told apart at a glance, which means they cannot all sit at the same weight. Light shipped
     * accent, success and warning within 0.02 of each other and read as one wash of colour.
     */
    @Test
    void semanticSlotsAreDistinguishableFromEachOther() throws IOException {
        for (Theme theme : shipped()) {
            var seen = new ArrayList<String>();
            for (String slot : List.of("accent", "success", "error", "warning")) {
                String colour = theme.resolve(slot);
                if (colour != null && HEX.matcher(colour).matches())
                    seen.add(colour);
            }
            assertEquals(seen.size(), seen.stream().distinct().count(),
                                            theme.name() + " reuses one colour for two meanings: " + seen);
        }
    }

    /**
     * The effort caret is the one glyph that has to encode a magnitude, so its seven steps must climb on some axis a
     * reader can perceive. Which axis depends on the background: on a dark one anything can be bright, so saturation
     * carries the signal; on a light one a saturated colour has to be dark to be seen at all, so contrast does.
     * Requiring either lets a theme pick its own story and still forbids the scatter all four shipped with -- gruvbox
     * dark went brown, aqua, green, pink, orange, red, which is a palette, not a scale.
     */
    @Test
    void theThinkingRampActuallyRamps() throws IOException {
        for (Theme theme : shipped()) {
            var sats = new ArrayList<Double>();
            var contrasts = new ArrayList<Double>();
            for (ThinkingLevel level : ThinkingLevel.values()) {
                String colour = theme.resolve(level.themeSlot());
                assertNotNull(colour, theme.name() + " has no colour for " + level);
                assertTrue(HEX.matcher(colour).matches(), theme.name() + "." + level + " is not a colour: " + colour);
                sats.add(saturation(colour));
                contrasts.add(contrast(colour, theme.background()));
            }

            assertTrue(climbs(sats) || climbs(contrasts), theme.name()
                                            + " thinking ramp climbs on neither saturation nor contrast, so no reader "
                                            + "can tell one level from the next:\n    saturation " + rounded(sats)
                                            + "\n    contrast   " + rounded(contrasts));
        }
    }

    /**
     * Token kinds have to be told apart, which is the whole point of colouring them. Both gruvbox themes painted
     * {@code syntaxFunction} and {@code syntaxString} the same green, so a string literal and the function it was
     * passed to were indistinguishable in the one thing this tool renders most.
     */
    @Test
    void syntaxColoursDoNotCollide() throws IOException {
        var kinds = List.of("syntaxComment", "syntaxKeyword", "syntaxFunction", "syntaxVariable", "syntaxString",
                                        "syntaxNumber", "syntaxType");
        for (Theme theme : shipped()) {
            var byColour = new java.util.LinkedHashMap<String, List<String>>();
            for (String kind : kinds)
                byColour.computeIfAbsent(theme.resolve(kind), ignored -> new ArrayList<>()).add(kind);

            var collisions = byColour.entrySet().stream().filter(e -> e.getValue().size() > 1)
                                            .map(e -> e.getValue() + " are all " + e.getKey()).toList();
            assertTrue(collisions.isEmpty(), theme.name() + " cannot tell two token kinds apart: " + collisions);
        }
    }

    /** Seven levels, seven colours: two levels that look the same are one level as far as anyone can see. */
    @Test
    void everyThinkingLevelLooksDifferent() throws IOException {
        for (Theme theme : shipped()) {
            var colours = new ArrayList<String>();
            for (ThinkingLevel level : ThinkingLevel.values())
                colours.add(theme.resolve(level.themeSlot()));

            assertEquals(colours.size(), colours.stream().distinct().count(),
                                            theme.name() + " repeats a ramp colour: " + colours);
        }
    }

    /**
     * A colour written once, in place, cannot be reused or adjusted, and it is how dark and light grew to 28 and 27
     * colours from 14 and 15 names. Gruvbox routed everything through its palette from the start, which is why it was
     * the coherent one.
     */
    @Test
    void everySlotIsANamedColourRatherThanAnInlineHex() throws IOException {
        for (Theme theme : shipped()) {
            var inline = theme.colors().entrySet().stream().filter(e -> HEX.matcher(e.getValue()).matches())
                                            .map(java.util.Map.Entry::getKey).sorted().toList();

            assertTrue(inline.isEmpty(), theme.name() + " writes a raw hex where a var belongs: " + inline);
        }
    }

    private static boolean climbs(List<Double> values) {
        for (int i = 0; i < values.size() - 1; i++)
            if (values.get(i) > values.get(i + 1) + 1e-9)
                return false;
        return true;
    }

    private static List<String> rounded(List<Double> values) {
        return values.stream().map("%.2f"::formatted).toList();
    }
}
