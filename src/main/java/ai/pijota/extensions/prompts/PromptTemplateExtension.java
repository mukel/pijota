package ai.pijota.extensions.prompts;

import ai.pijota.config.ProjectTrust;
import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.modes.SlashCommand;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PromptTemplateExtension implements Extension {
    private final List<String> explicitPaths;
    private final List<PromptTemplate> templates;
    private ExtensionAPI api;

    public PromptTemplateExtension(List<String> explicitPaths) {
        this.explicitPaths = explicitPaths == null ? List.of() : List.copyOf(explicitPaths);
        this.templates = new CopyOnWriteArrayList<>();
    }

    @Override
    public String name() {
        return "prompt-templates";
    }

    @Override
    public String description() {
        return "Prompt template expansion via /<name> <args>";
    }

    @Override
    public void init(ExtensionAPI api) {
        this.api = api;
        reloadTemplates();
    }

    private void reloadTemplates() {
        String cwd = api.runtime().cwd().toString();
        var loaded = PromptTemplateLoader.load(cwd, explicitPaths, ProjectTrust.isTrusted(cwd));
        templates.clear();
        templates.addAll(loaded);
        if (templates.isEmpty())
            return;

        // register each template as a slash command
        for (var t : templates) {
            api.registerSlashCommand(new SlashCommand(t.name(), t.description(), t.argumentHint(), List.of(), args -> {
                String expanded = PromptTemplateLoader.expand(t.content(), PromptTemplateLoader.parseArgs(args));
                api.sendUserMessage(expanded);
            }));
        }

        // register /prompts to list available templates
        api.registerSlashCommand(new SlashCommand("prompts", "List available prompt templates", List.of(), _args -> {
            var sb = new StringBuilder();
            templates.stream().sorted(Comparator.comparing(PromptTemplate::name)).forEach(t -> {
                sb.append("\n  /").append(t.name());
                if (t.argumentHint() != null)
                    sb.append(' ').append(t.argumentHint());
                sb.append(" — ").append(t.description());
            });
            api.runtime().setStatus(sb.toString().stripLeading());
        }));

        // contribute to system prompt so LLM knows about available templates
        api.registerSystemPrompt(() -> PromptTemplateLoader.buildPrompt(templates));
    }
}
