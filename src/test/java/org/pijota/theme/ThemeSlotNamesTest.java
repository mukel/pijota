package org.pijota.theme;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards the one failure mode {@link AnsiTheme#style} cannot report: an unknown slot name resolves to the empty string,
 * so a typo renders unstyled text and everything downstream keeps working. That silence has already cost a false
 * passing test in this repo -- an assertion of the form {@code out.contains(theme.style("underline"))} succeeded for
 * every input, because "underline" was not a slot, {@code style} returned "", and every string contains "".
 *
 * <p>
 * Only string literals can be checked, which is exactly the point: a literal is where a typo hides, while a computed
 * slot name is already the caller's own variable.
 */
class ThemeSlotNamesTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path THEME_SOURCE = MAIN.resolve("org/pijota/theme/AnsiTheme.java");

    /**
     * {@code style("x")} / {@code styled("x", …)} with a literal name, with or without a receiver: several renderers
     * call an unqualified local helper, and requiring the dot hid those call sites from this check entirely.
     */
    private static final Pattern USE = Pattern.compile("\\bstyled?\\(\"([a-zA-Z][a-zA-Z0-9]*)\"");

    /** The {@code addExtra(t, c, vars, "a", "b", …)} call that registers the optional, theme-file-supplied slots. */
    private static final Pattern ADD_EXTRA = Pattern.compile("addExtra\\(t, c, vars,(.*?)\\);", Pattern.DOTALL);

    private static Set<String> declaredFields() {
        return Arrays.stream(AnsiTheme.class.getFields()).filter(f -> f.getType() == String.class)
                                        .map(java.lang.reflect.Field::getName)
                                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

    /** Extra slots are not fields; they live in a map fed by theme files, so read the registration from the source. */
    private static Set<String> registeredExtras() throws IOException {
        String src = Files.readString(THEME_SOURCE, StandardCharsets.UTF_8);
        var m = ADD_EXTRA.matcher(src);
        assertTrue(m.find(), "addExtra(...) registration not found in " + THEME_SOURCE + "; update this test");
        var names = new TreeSet<String>();
        var literal = Pattern.compile("\"([a-zA-Z][a-zA-Z0-9]*)\"").matcher(m.group(1));
        while (literal.find())
            names.add(literal.group(1));
        assertFalse(names.isEmpty(), "no extra slots parsed; the registration shape changed");
        return names;
    }

    private static Stream<Path> mainSources() throws IOException {
        try (var walk = Files.walk(MAIN)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList().stream();
        }
    }

    @Test
    void everySlotNameUsedInRenderingCodeExists() throws IOException {
        Set<String> known = new LinkedHashSet<>(declaredFields());
        known.addAll(registeredExtras());

        var unknown = new TreeMap<String, String>(); // slot -> first file that uses it
        for (Path file : mainSources().toList()) {
            if (file.equals(THEME_SOURCE))
                continue; // the theme's own source defines the names rather than consuming them
            var m = USE.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find())
                if (!known.contains(m.group(1)))
                    unknown.putIfAbsent(m.group(1), MAIN.relativize(file).toString());
        }
        assertTrue(unknown.isEmpty(), () -> "slot names that resolve to no style, so their text renders unstyled: "
                                        + unknown + "\nAdd the field to AnsiTheme, register it in addExtra(...), or "
                                        + "fix the typo.");
    }

    /**
     * An extra slot absent from a shipped theme silently renders unstyled, and the two built-ins are what the default
     * look is. A theme file left out {@code mdListBullet} once; nothing failed, bullets just stopped being coloured.
     */
    @Test
    void theBuiltInThemesDefineEveryExtraSlot() throws IOException {
        Set<String> extras = registeredExtras();
        for (String theme : new String[] {"dark", "light"}) {
            Path file = Path.of("src/main/resources/themes", theme + ".json");
            assertTrue(Files.exists(file), file + " is missing");
            String json = Files.readString(file, StandardCharsets.UTF_8);
            var missing = extras.stream().filter(slot -> !json.contains("\"" + slot + "\"")).toList();
            assertTrue(missing.isEmpty(), () -> theme + ".json does not define " + missing
                                            + ", so those render unstyled in the default theme");
        }
    }

    /**
     * The extra slots are extension-facing API: any extension may call {@code styled("success", …)}, and several of
     * them (success, warning, userMessageText) have no in-tree caller, so nothing else would notice if they stopped
     * resolving. What matters is not that they are used here but that they produce a style when used.
     *
     * <p>
     * Loaded the way the app loads them -- {@code themes/<name>.json} from the classpath, which is what
     * {@code ThemeLoader.loadBuiltin} does -- rather than through {@link AnsiTheme#dark()}, which populates no extras
     * at all and exists only as the fallback for a theme resource that cannot be read.
     */
    @Test
    void everyExtraSlotResolvesToARealStyleInTheBuiltInThemes() throws IOException {
        Set<String> extras = registeredExtras();
        for (String name : new String[] {"dark", "light"}) {
            AnsiTheme theme = AnsiTheme.loadResource("themes/" + name + ".json");
            var unstyled = extras.stream().filter(slot -> theme.style(slot).isEmpty()).toList();
            assertTrue(unstyled.isEmpty(), () -> name + ".json: these resolve to no escape sequence, so an extension "
                                            + "styling with them silently renders plain text: " + unstyled);
        }
    }

    /**
     * The thinking levels name their slots from the enum rather than as {@code styled("literal")}, so {@link #USE}
     * cannot see them and the check above cannot cover them. It matters: every shipped theme defines the ramp, and
     * {@code thinkingMax} was nonetheless absent from {@code addExtra}, so the loudest level of the ramp -- the one
     * that says you are buying maximum reasoning -- was the one that rendered unstyled.
     */
    @Test
    void everyThinkingLevelHasARegisteredSlotThatResolves() throws IOException {
        Set<String> known = new LinkedHashSet<>(declaredFields());
        known.addAll(registeredExtras());
        AnsiTheme theme = AnsiTheme.loadResource("themes/dark.json");
        for (var level : org.pijota.llm.ThinkingLevel.values()) {
            assertTrue(known.contains(level.themeSlot()), level + " uses slot \"" + level.themeSlot()
                                            + "\", which is not registered");
            assertFalse(theme.style(level.themeSlot()).isEmpty(),
                                            level + "'s slot resolves to no style, so the level renders unstyled");
        }
    }

    /**
     * {@link AnsiTheme#dark()} is the fallback when the theme resource cannot be read -- a real possibility in a native
     * image, where a resource must be registered to be present. It populates no extra slots, so that fallback silently
     * drops link colours, list bullets and custom-message styling with no error anywhere. Pinned rather than fixed: the
     * gap is worth knowing about, and closing it means duplicating the JSON in Java.
     */
    @Test
    void theProgrammaticFallbackThemeIsKnownToCarryNoExtras() throws IOException {
        Set<String> extras = registeredExtras();
        assertTrue(extras.stream().allMatch(slot -> AnsiTheme.dark().style(slot).isEmpty()),
                                        "AnsiTheme.dark() gained extras; fold this into the resource-loaded test");
    }

}
