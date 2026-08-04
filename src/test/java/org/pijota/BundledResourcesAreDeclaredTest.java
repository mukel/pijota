package org.pijota;

import org.pijota.extensions.skills.SkillLoader;
import org.pijota.extensions.task.SubAgent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A classpath directory cannot be enumerated in a native image, so every bundled skill, agent and theme is loaded by
 * name from a hand-written list. Add the file and forget the list and it works on the JVM, passes the whole suite, and
 * simply does not exist in the shipped binary.
 *
 * <p>
 * That is the same failure that killed {@code pj worktree list} in every release build. The lists are short and rarely
 * touched, which is exactly why nobody remembers they need touching.
 *
 * <p>
 * Themes already have this guard in {@code ThemeManagerTest}; skills and agents did not.
 */
class BundledResourcesAreDeclaredTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path RESOURCE_CONFIG = Path.of(
                                    "src/main/resources/META-INF/native-image/org.pijota/pijota/resource-config.json");

    @Test
    void everyBundledSkillIsInTheBuiltInList() throws IOException {
        assertDeclared("skills", ".md", builtins(SkillLoader.class, "BUILTIN_SKILLS"));
    }

    @Test
    void everyBundledAgentIsInTheBuiltInList() throws IOException {
        assertDeclared("agents", ".md", builtins(SubAgent.class, "BUILTIN_AGENTS"));
    }

    /** The lists are the authority at runtime, so a name in one with no file behind it is a load that always fails. */
    private static void assertDeclared(String dir, String extension, List<String> declared) throws IOException {
        List<String> onDisk;
        try (Stream<Path> files = Files.list(RESOURCES.resolve(dir))) {
            onDisk = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(extension))
                                            .map(n -> n.substring(0, n.length() - extension.length())).sorted()
                                            .toList();
        }

        assertFalse(onDisk.isEmpty(), "no bundled " + dir + " found; this test would assert nothing");
        assertEquals(onDisk, declared.stream().sorted().toList(),
                                        dir + "/ on disk and the built-in list must agree, or the difference silently "
                                                                        + "disappears in the native image");
    }

    /** Resource patterns are wildcards, so a new file is covered automatically. This checks the wildcard is there. */
    @Test
    void theResourceConfigCoversEveryBundledDirectory() throws IOException {
        String config = Files.readString(RESOURCE_CONFIG);
        for (String dir : List.of("skills", "agents", "themes"))
            assertTrue(config.contains(dir + "/"), dir + "/ is not in resource-config.json, so nothing under it ships: "
                                            + RESOURCE_CONFIG);
    }

    @SuppressWarnings("unchecked")
    private static List<String> builtins(Class<?> owner, String field) {
        try {
            var f = owner.getDeclaredField(field);
            f.setAccessible(true);
            return (List<String>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(owner.getSimpleName() + "." + field + " is gone; if the loading strategy "
                                            + "changed, this guard needs to change with it", e);
        }
    }
}
