package ai.pijota.tui;

import ai.pijota.extensions.skills.SkillLoader;

import java.util.ArrayList;
import java.util.List;

final class SkillCommandHandler {
    interface Host {
        List<SkillLoader.Skill> skills();

        String styled(String slot, String text);

        void appendPlain(List<String> lines);

        List<String> promptEcho(String text);

        long beginBusy(String phase);

        void redrawPrompt();

        void submitAgentPrompt(String prompt, Runnable after);

        void endBusy(long generation);

        void showSessionWarning();
    }

    private final Host host;

    SkillCommandHandler(Host host) {
        this.host = host;
    }

    void showSkills() {
        var skills = host.skills();
        if (skills.isEmpty()) {
            host.appendPlain(List.of(host.styled("statusText", "no skills found")));
            return;
        }
        var lines = new ArrayList<String>();
        lines.add(host.styled("toolLabel", "skills (" + skills.size() + ")"));
        for (var s : skills) {
            String desc = s.description() != null && !s.description().isBlank() ? "  " + s.description() : "";
            lines.add(host.styled("statusText", "  " + s.name()) + host.styled("commandMeta", desc));
        }
        lines.add(host.styled("statusText", "usage: /skill:<name> [instructions]"));
        host.appendPlain(lines);
    }

    void handleSkillCommand(String input) {
        var parsed = parseSkillCommand(input);
        var skill = SkillLoader.findByName(host.skills(), parsed.name());
        if (skill == null) {
            host.appendPlain(List.of(host.styled("toolError", "skill not found: " + parsed.name())));
            return;
        }
        String expanded = expandedPrompt(skill, parsed.instructions());
        host.appendPlain(host.promptEcho(input));
        long gen = host.beginBusy("waiting for model");
        host.redrawPrompt();
        host.submitAgentPrompt(expanded, () -> {
            host.showSessionWarning();
            host.endBusy(gen);
        });
    }

    static ParsedSkillCommand parseSkillCommand(String input) {
        String rest = input.substring("/skill:".length());
        String name = rest;
        String instructions = "";
        int space = rest.indexOf(' ');
        if (space > 0) {
            name = rest.substring(0, space);
            instructions = rest.substring(space + 1).trim();
        }
        return new ParsedSkillCommand(name, instructions);
    }

    static String expandedPrompt(SkillLoader.Skill skill, String instructions) {
        return "# Skill: " + skill.name() + "\n\n" + skill.content()
                                        + (instructions == null || instructions.isEmpty()
                                                                        ? ""
                                                                        : "\n\n# Instructions\n\n" + instructions);
    }

    record ParsedSkillCommand(String name, String instructions) {
    }
}
