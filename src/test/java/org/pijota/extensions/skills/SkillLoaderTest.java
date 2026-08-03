package org.pijota.extensions.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderTest {

    /**
     * The meta skill ships so a fresh install can answer "how do I configure pijota" from inside pijota. Skills are
     * discovered from directories, and a classpath directory cannot be enumerated in a native image, so a built-in that
     * is not in BUILTIN_SKILLS (or not in resource-config.json) simply is not there in the shipped binary.
     */
    @Test
    void theBuiltInPijotaSkillIsAlwaysAvailable(@TempDir Path cwd) {
        var skills = SkillLoader.load(cwd.toString(), List.of());

        var pijota = skills.stream().filter(s -> s.name().equals("pijota")).findFirst();
        assertTrue(pijota.isPresent(), "the shipped meta skill is missing: "
                                        + skills.stream().map(SkillLoader.Skill::name).toList());
        assertTrue(pijota.get().description().toLowerCase().contains("configure"),
                                        "its description is what routes it: " + pijota.get().description());
        assertFalse(pijota.get().content().isBlank(), "a skill with no body teaches nothing");
    }

    /**
     * A skill that names a command or flag pijota does not have is worse than no skill: the model repeats it with
     * confidence and the user is sent somewhere that does not exist. This session produced several such inventions (a
     * web port, model ids, keybinding names), which is exactly why the shipped skill is checked against reality.
     */
    @Test
    void theBuiltInSkillOnlyNamesCommandsAndFlagsThatExist(@TempDir Path cwd) throws Exception {
        String body = SkillLoader.load(cwd.toString(), List.of()).stream().filter(s -> s.name().equals("pijota"))
                                        .findFirst().orElseThrow().content();
        // The whole tree, not one registry: /trust comes from an extension and --no-jobs / --transcendence are
        // extension flags, so a narrower search reports things that plainly do exist.
        String source = sourceTree();

        var missing = new java.util.ArrayList<String>();
        var named = java.util.regex.Pattern.compile("`(/[a-z-]+|--[a-z-]+)[`< |]").matcher(body);
        while (named.find()) {
            String token = named.group(1);
            // --no-<extension> is built at runtime as "no-" + the extension's own name, so the literal never appears
            // in the source; what has to exist is the extension.
            String bare = token.startsWith("--no-")
                                            ? token.substring(5)
                                            : token.startsWith("--") ? token.substring(2) : token.substring(1);
            if (!source.contains('"' + token + '"') && !source.contains('"' + bare + '"'))
                missing.add(token);
        }

        assertTrue(missing.isEmpty(), "the shipped skill names things pijota does not have: " + missing);
    }

    private static String sourceTree() throws java.io.IOException {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            var sb = new StringBuilder();
            for (Path f : files.filter(f -> f.toString().endsWith(".java")).toList())
                sb.append(Files.readString(f));
            return sb.toString();
        }
    }

    /** A user's own skill of the same name must win; a shipped default that cannot be replaced is a trap. */
    @Test
    void aUserSkillShadowsTheBuiltInOfTheSameName(@TempDir Path cwd) throws Exception {
        Path dir = cwd.resolve(".pijota/skills");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pijota.md"), "---\nname: pijota\ndescription: mine\n---\nmy own body\n");

        var skills = SkillLoader.load(cwd.toString(), List.of());

        var pijota = skills.stream().filter(s -> s.name().equals("pijota")).findFirst().orElseThrow();
        assertEquals("mine", pijota.description(), "the built-in shadowed the user's own skill");
        assertEquals(1, skills.stream().filter(s -> s.name().equals("pijota")).count(), "listed twice");
    }

    @Test
    void parseFrontmatterWithValidYaml() {
        var result = SkillLoader.parseFrontmatter(
                                        "---\nname: myskill\ndescription: does things\n---\n\nskill body here");
        assertEquals("myskill", result.frontmatter().get("name"));
        assertEquals("does things", result.frontmatter().get("description"));
        assertEquals("skill body here", result.body());
    }

    @Test
    void parseFrontmatterWithoutFrontmatter() {
        var result = SkillLoader.parseFrontmatter("just plain text\nno frontmatter");
        assertTrue(result.frontmatter().isEmpty());
        assertEquals("just plain text\nno frontmatter", result.body());
    }

    @Test
    void parseFrontmatterEmpty() {
        var result = SkillLoader.parseFrontmatter("");
        assertTrue(result.frontmatter().isEmpty());
        assertEquals("", result.body());
    }

    @Test
    void parseFrontmatterOnlySeparators() {
        var result = SkillLoader.parseFrontmatter("---\n---");
        assertTrue(result.frontmatter().isEmpty());
        assertEquals("", result.body());
    }

    @Test
    void parseFrontmatterEmptyValueSkipped() {
        var result = SkillLoader.parseFrontmatter("---\nname: \ndescription: test\n---\nbody");
        assertFalse(result.frontmatter().containsKey("name"));
        assertEquals("test", result.frontmatter().get("description"));
    }

    @Test
    void buildPromptWithSkills() {
        var skill = new SkillLoader.Skill("test", "A test skill", "skill content", null);
        var prompt = SkillLoader.buildPrompt(List.of(skill));
        assertTrue(prompt.contains("<available_skills>"));
        assertTrue(prompt.contains("<name>test</name>"));
        assertTrue(prompt.contains("A test skill"));
    }

    @Test
    void buildPromptWithMultipleSkills() {
        var s1 = new SkillLoader.Skill("a", "first", "", null);
        var s2 = new SkillLoader.Skill("b", "second", "", null);
        var prompt = SkillLoader.buildPrompt(List.of(s1, s2));
        assertTrue(prompt.contains("first"));
        assertTrue(prompt.contains("second"));
    }

    @Test
    void buildPromptEmptySkills() {
        assertEquals("", SkillLoader.buildPrompt(List.of()));
    }

    @Test
    void findByNameCaseInsensitive() {
        var s1 = new SkillLoader.Skill("MySkill", "desc", "", null);
        assertNotNull(SkillLoader.findByName(List.of(s1), "myskill"));
        assertNotNull(SkillLoader.findByName(List.of(s1), "MYSKILL"));
        assertNotNull(SkillLoader.findByName(List.of(s1), "MySkill"));
    }

    @Test
    void findByNameNotFound() {
        assertNull(SkillLoader.findByName(List.of(), "none"));
        assertNull(SkillLoader.findByName(List.of(new SkillLoader.Skill("a", "d", "", null)), "b"));
    }

    @Test
    void skillRecordEquality() {
        var a = new SkillLoader.Skill("n", "d", "c", null);
        var b = new SkillLoader.Skill("n", "d", "c", null);
        assertEquals(a, b);
    }
}
