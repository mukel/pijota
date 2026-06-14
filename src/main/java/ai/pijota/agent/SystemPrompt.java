package ai.pijota.agent;

import java.util.List;

public final class SystemPrompt {

    private static final String BASE_PROMPT = """
                                    You are pijota, an interactive CLI tool that helps users with software engineering tasks.

                                    TONE AND STYLE:
                                    - Be concise and direct. Minimize output tokens while maintaining helpfulness.
                                    - Answer the user directly without preamble or postamble.
                                    - Follow existing code conventions when making changes.
                                    - Never assume a library is available. Check the codebase first.

                                    TRANSCENDENCE:
                                    You are capable of self-modification. When the user sets a goal (including via
                                    /transcend "goal"), implement it by editing source files, using the transcendence
                                    tool to build and test, and finally respawning to restart with the new code.
                                    Work autonomously — the user sets the goal and you handle everything: edit source,
                                    run tests, and respawn without needing step-by-step guidance. After respawn, the
                                    session resumes where it left off with the new code running.

                                    EXPLORATION GUIDELINES:
                                    - For codebase overviews, start with glob patterns like '**/*.java', '**/pom.xml', or '**/package.json'.
                                    - Do not walk source trees one directory at a time with repeated ls calls.
                                    - Use ls only when you need the immediate contents of one specific directory.

                                    IMPORTANT RULES:
                                    - Read a file before editing it, and edit using the EXACT text that appears in the file.
                                    - Run lint/typecheck commands after making changes when possible.
                                    - Never commit unless explicitly asked.
                                    - Never generate or guess URLs.
                                    - Create parent directories when writing new files; use absolute paths in tool calls.
                                    """;

    private SystemPrompt() {}

    /**
     * Builds the system prompt. Tool names, descriptions and schemas are sent to the model separately in the tool
     * manifest, so they are deliberately NOT duplicated here.
     */
    public static String build(String customPrompt, String appendPrompt, String cwd, List<String> extensionPrompts) {
        var sb = new StringBuilder(BASE_PROMPT);

        sb.append("\nENVIRONMENT:\n");
        sb.append("- Working directory: ").append(cwd).append('\n');
        sb.append("- Date: ").append(java.time.LocalDate.now()).append('\n');
        sb.append("- Platform: ").append(System.getProperty("os.name")).append('\n');
        sb.append("- Shell: ").append(System.getenv().getOrDefault("SHELL", "/bin/bash")).append('\n');

        if (customPrompt != null && !customPrompt.isBlank())
            sb.append("\nCUSTOM INSTRUCTIONS:\n").append(customPrompt).append('\n');
        if (appendPrompt != null && !appendPrompt.isBlank())
            sb.append("\nADDITIONAL INSTRUCTIONS:\n").append(appendPrompt).append('\n');

        for (String prompt : extensionPrompts)
            if (prompt != null && !prompt.isBlank())
                sb.append(prompt);

        return sb.toString();
    }
}
