package org.pijota;

import static org.junit.jupiter.api.Assertions.*;

import org.pijota.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

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

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path CONFIG = RESOURCES
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
     * What the build actually packages: everything under resources, minus the pom's excludes, plus the files it
     * packages from the repository root (LICENSE and NOTICE, which live there so there is only one copy of each).
     */
    private static List<String> packaged() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        var out = new TreeSet<>(tags(pom, "include"));
        var excluded = tags(pom, "exclude");
        try (var walk = Files.walk(RESOURCES)) {
            walk.filter(Files::isRegularFile).map(p -> RESOURCES.relativize(p).toString().replace('\\', '/'))
                                            // The native-image config describes the image; it is not itself loaded.
                                            .filter(name -> !name.startsWith("META-INF/native-image/"))
                                            .filter(name -> !excluded.contains(name)).forEach(out::add);
        }
        assertFalse(out.isEmpty(), "no packaged resources found; the pom's <resources> shape changed");
        return List.copyOf(out);
    }

    private static TreeSet<String> tags(String pom, String tag) {
        var out = new TreeSet<String>();
        var m = Pattern.compile("<" + tag + ">([^<]+)</" + tag + ">").matcher(pom);
        while (m.find())
            out.add(m.group(1));
        return out;
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
        // Patterns for resources that come from dependencies or the build, not from this tree.
        var fromElsewhere = List.of("META-INF/services/.*", "META-INF/jline/.*");
        for (var pattern : registered()) {
            if (fromElsewhere.contains(pattern.pattern()))
                continue;
            assertTrue(packaged.stream().anyMatch(name -> pattern.matcher(name).matches()),
                                            "pattern \"" + pattern.pattern() + "\" matches nothing this build "
                                                                            + "packages; it is a typo or a leftover");
        }
    }
}
