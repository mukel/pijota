package org.pijota.extensions.skills;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.modes.SlashCommand;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Discovers skills and exposes them to the model, from the bundled set, the user's home and a trusted project. */
public final class SkillExtension implements Extension {
    private final List<String> explicitPaths;
    private final List<SkillLoader.Skill> skills;
    private ExtensionAPI api;

    public SkillExtension(List<String> explicitPaths) {
        this.explicitPaths = explicitPaths == null ? List.of() : List.copyOf(explicitPaths);
        this.skills = new CopyOnWriteArrayList<>();
    }

    @Override
    public String name() {
        return "skills";
    }

    @Override
    public String description() {
        return "Skill discovery and skill instruction loading";
    }

    @Override
    public void init(ExtensionAPI api) {
        this.api = api;
        reloadSkills();

        api.registerSlashCommand(new SlashCommand("skills", "List available skills", List.of(), _args -> {
            if (skills.isEmpty()) {
                api.runtime().setStatus("No skills loaded");
                return;
            }
            var sb = new StringBuilder();
            skills.stream().sorted(Comparator.comparing(SkillLoader.Skill::name)).forEach(s -> {
                sb.append("\n  ").append(s.name()).append(" · ").append(s.description());
            });
            api.runtime().setStatus(sb.toString().stripLeading());
        }));
    }

    private void reloadSkills() {
        var loaded = SkillLoader.load(api.runtime().cwd().toString(), explicitPaths);
        skills.clear();
        skills.addAll(loaded);
        if (!skills.isEmpty()) {
            api.registerSkills(skills);
            api.registerTool(new SkillTool(skills));
            api.registerSystemPrompt(() -> SkillLoader.buildPrompt(skills));
        }
    }
}
