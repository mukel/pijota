package org.pijota;

import org.pijota.util.Util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A resource the build packages but the native image was not told to keep is present in the jar and absent from the
 * binary, and {@code getResourceAsStream} answers null rather than failing, so the feature that needs it quietly does
 * nothing.
 *
 * <p>
 * That is not hypothetical: {@code browser/*.js} shipped unregistered for as long as the browse extension existed, so
 * every injected script -- Readability, reader, page, search, consent, pdf -- was an empty string in the native binary
 * and {@code --browse} extracted nothing at all without a word. The literals that would have caught it are not literals
 * at all: the scripts load through a {@code loadResource(path)} helper, as do the themes and the license texts, so no
 * scan of {@code getResourceAsStream("...")} arguments can see them.
 *
 * <p>
 * The rule this checks is therefore about shipping rather than loading: if the build packages it, register it. A
 * registered resource that nothing loads costs a few bytes; an unregistered one that something loads is a silent
 * feature failure in the release artifact only.
 */
class NativeImageResourcesTest {

    private static final Path CLASSES = Path.of("target/classes");
    private static final Path CONFIG = Path.of("src/main/resources")
                                    .resolve("META-INF/native-image/org.pijota/pijota/resource-config.json");

    /** The include patterns the native image is given, as compiled regexes. */
    private static List<Pattern> registered() throws IOException {
        var json = Util.MAPPER.readTree(Files.readString(CONFIG, StandardCharsets.UTF_8));
        var out = new ArrayList<Pattern>();
        json.path("resources").path("includes")
                                        .forEach(node -> out.add(Pattern.compile(node.path("pattern").asText())));
        assertFalse(out.isEmpty(), "no include patterns parsed from " + CONFIG + "; the config shape changed");
        return out;
    }

    /**
     * What the build actually packages, read from the build output rather than from {@code src/main/resources}.
     *
     * <p>
     * That distinction is the whole point. Six of these files exist only here: pdf.js and the web-UI libraries are
     * downloaded into {@code target/classes} at generate-resources, and inter.woff2 with them, so a check against the
     * source tree cannot see them at all -- they are covered by the {@code web/.*} and {@code browser/.*\.js} globs
     * only by luck, and retargeting a download to a path those globs miss would ship a binary without it while this
     * test stayed green. Reading the output directory also makes the pom's includes and excludes moot: Maven has
     * already applied them.
     */
    private static TreeSet<String> packaged() throws IOException {
        try (var walk = Files.walk(CLASSES)) {
            var out = walk.filter(Files::isRegularFile).map(p -> CLASSES.relativize(p).toString().replace('\\', '/'))
                                            .filter(name -> !name.endsWith(".class"))
                                            // The native-image config describes the image; it is not itself loaded.
                                            .filter(name -> !name.startsWith("META-INF/native-image/"))
                                            .collect(Collectors.toCollection(TreeSet::new));
            assertFalse(out.isEmpty(), "no packaged resources under " + CLASSES + "; run the build first");
            return out;
        }
    }

    @Test
    void everyPackagedResourceIsKeptInTheNativeImage() throws IOException {
        var patterns = registered();
        var unregistered = packaged().stream()
                                        .filter(name -> patterns.stream().noneMatch(p -> p.matcher(name).matches()))
                                        .toList();

        assertTrue(unregistered.isEmpty(), () -> "packaged but not registered for the native image, so these are in "
                                        + "the jar and missing from the binary: " + unregistered + "\nAdd a pattern to "
                                        + CONFIG);
    }

    /** The texts {@code --license} exists to deliver, which reach the binary through that same list. */
    @Test
    void theLicenseTextsAreKept() throws IOException {
        var patterns = registered();
        for (String required : List.of("LICENSE", "NOTICE"))
            assertTrue(patterns.stream().anyMatch(p -> p.matcher(required).matches()),
                                            required + " is not registered, so --license would exit non-zero on the "
                                                                            + "native binary");
    }

    /** A pattern matching nothing is a rule that has outlived what it protected, or a typo that protects nothing. */
    @Test
    void noRegisteredPatternMatchesNothing() throws IOException {
        var packaged = packaged();
        for (var pattern : registered()) {
            // META-INF entries come from dependency jars, which land in the shaded artifact rather than here.
            if (pattern.pattern().startsWith("META-INF/"))
                continue;
            assertTrue(packaged.stream().anyMatch(name -> pattern.matcher(name).matches()),
                                            "pattern \"" + pattern.pattern() + "\" matches nothing this build "
                                                                            + "packages; it is a typo or a leftover");
        }
    }
}
