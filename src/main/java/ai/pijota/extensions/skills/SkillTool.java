package ai.pijota.extensions.skills;

import ai.pijota.tools.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class SkillTool implements Tool {

    private final List<SkillLoader.Skill> skills;

    public SkillTool(List<SkillLoader.Skill> skills) {
        this.skills = skills;
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return "Load a skill's instructions by name. Use when a task matches a skill description.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("type", "object", "properties",
                                        Map.of("name", Map.of("type", "string", "description",
                                                                        "The skill name to load")),
                                        "required", List.of("name"));
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Map<String, Object> args, String cwd) {
        String name = (String) args.get("name");
        if (name == null || name.isBlank()) {
            return CompletableFuture.completedFuture(ToolResult.error("Missing skill name"));
        }
        var skill = SkillLoader.findByName(skills, name);
        if (skill == null) {
            return CompletableFuture.completedFuture(ToolResult.error("Skill not found: " + name));
        }
        return CompletableFuture.completedFuture(ToolResult
                                        .success("# Skill: " + skill.name() + "\n\n" + skill.content() + "\n"));
    }
}
