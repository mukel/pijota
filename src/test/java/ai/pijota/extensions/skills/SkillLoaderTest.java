package ai.pijota.extensions.skills;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderTest {

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
