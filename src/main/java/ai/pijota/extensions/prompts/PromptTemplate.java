package ai.pijota.extensions.prompts;

import java.nio.file.Path;

public record PromptTemplate(
    String name,          // command name, e.g. "review"
    String description,   // shown in /help and /prompts
    String argumentHint,  // shown in autocomplete, e.g. "<PR-URL>"
    String content,       // template body with $1, $2, $@, etc.
    Path source           // file path for display
) {}
