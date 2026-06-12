package ai.pijota.extensions.skills;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillToolTest {

    @Test
    void nameIsSkill() {
        var tool = new SkillTool(List.of());
        assertEquals("skill", tool.name());
    }

    @Test
    void executeWithFoundSkillReturnsContent() throws Exception {
        var skill = new SkillLoader.Skill("test", "A test skill", "skill body content", null);
        var tool = new SkillTool(List.of(skill));
        var result = tool.execute("call-1", java.util.Map.of("name", "test"), "/cwd").get();
        assertFalse(result.isError());
        assertTrue(result.content().contains("test"));
        assertTrue(result.content().contains("skill body content"));
    }

    @Test
    void executeCaseInsensitiveName() throws Exception {
        var skill = new SkillLoader.Skill("TestSkill", "desc", "content", null);
        var tool = new SkillTool(List.of(skill));
        var result = tool.execute("call-1", java.util.Map.of("name", "testskill"), "/cwd").get();
        assertFalse(result.isError());
    }

    @Test
    void executeMissingSkillReturnsError() throws Exception {
        var tool = new SkillTool(List.of());
        var result = tool.execute("call-1", java.util.Map.of("name", "nonexistent"), "/cwd").get();
        assertTrue(result.isError());
        assertTrue(result.content().contains("not found"));
    }

    @Test
    void executeBlankNameReturnsError() throws Exception {
        var tool = new SkillTool(List.of());
        var result = tool.execute("call-1", java.util.Map.of("name", "  "), "/cwd").get();
        assertTrue(result.isError());
        assertTrue(result.content().contains("Missing"));
    }

    @Test
    void executeNullNameReturnsError() throws Exception {
        var tool = new SkillTool(List.of());
        var result = tool.execute("call-1", java.util.Map.of(), "/cwd").get();
        assertTrue(result.isError());
    }

    @Test
    void defaultEnabledIsTrue() {
        assertTrue(new SkillTool(List.of()).defaultEnabled());
    }

    @Test
    void toDefinitionProducesValidTool() {
        var skill = new SkillLoader.Skill("test", "desc", "body", null);
        var tool = new SkillTool(List.of(skill));
        var def = tool.toDefinition();
        assertEquals("skill", def.name());
        assertNotNull(def.schema());
    }
}
