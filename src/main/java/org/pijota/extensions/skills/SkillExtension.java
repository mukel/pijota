package org.pijota.extensions.skills;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.modes.SlashCommand;

import java.util.ArrayList;
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
            // A long list in a transient toast can't scroll and vanishes (the web maps setStatus to a toast), so show
            // it in the scrollable viewer instead: a modal on the TUI, a collapsible transcript block on the web.
            // Descriptions can wrap to several lines, so blank-line-separate the entries to keep them scannable.
            var lines = new ArrayList<String>();
            skills.stream().sorted(Comparator.comparing(SkillLoader.Skill::name)).forEach(s -> {
                if (!lines.isEmpty())
                    lines.add("");
                lines.add(s.name() + " · " + s.description());
            });
            api.runtime().ui().viewer("skills (" + skills.size() + ")", lines);
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
