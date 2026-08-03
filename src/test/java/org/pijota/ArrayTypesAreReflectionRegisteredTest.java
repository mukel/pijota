package org.pijota;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Jackson cannot build {@code Foo[]} in a native image unless the array type itself is registered -- registering
 * {@code Foo} is not enough. The JVM hides this completely: the build passes, the whole suite passes, and the shipped
 * binary dies at the first call with "Cannot reflectively instantiate the array class".
 *
 * <p>
 * That is not hypothetical. {@code pj worktree list} was dead in every release build, and {@code Update[]} and
 * {@code TmuxSession[]} -- Telegram's update polling and tmux session listing -- were unregistered the same way, found
 * only by running the real binary.
 */
class ArrayTypesAreReflectionRegisteredTest {

    private static final Path METADATA = Path.of(
                                    "src/main/resources/META-INF/native-image/org.pijota/pijota/reachability-metadata.json");
    private static final Path SOURCES = Path.of("src/main/java");

    /** {@code readValue(json, Foo[].class)} and friends: the array is instantiated reflectively. */
    private static final Pattern ARRAY_CLASS_LITERAL = Pattern.compile("\\b(\\w+)\\[]\\.class");

    @Test
    void everyArrayTypeReadReflectivelyIsRegistered() throws IOException {
        String metadata = Files.readString(METADATA);
        var missing = new ArrayList<String>();

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                var matcher = ARRAY_CLASS_LITERAL.matcher(Files.readString(file));
                while (matcher.find()) {
                    String simple = matcher.group(1);
                    if (isBuiltin(simple))
                        continue;
                    // the metadata carries fully-qualified names; match on the simple name plus the array marker
                    if (!metadata.contains(simple + "[]"))
                        missing.add(simple + "[] (" + SOURCES.relativize(file) + ")");
                }
            }
        }

        assertTrue(missing.isEmpty(), "these array types would fail in the native binary but not on the JVM: " + missing
                                        + "\nadd each to " + METADATA + " as a bare {\"type\": \"...[]\"} entry");
    }

    /** Arrays of these are handled without reflection, so they need no entry. */
    private static boolean isBuiltin(String simple) {
        return switch (simple) {
            case "String", "byte", "char", "int", "long", "double", "float", "boolean", "short", "Object", "Byte",
                                            "Integer", "Long", "Double", "Float", "Boolean", "Character", "Short" ->
                true;
            default -> false;
        };
    }
}
