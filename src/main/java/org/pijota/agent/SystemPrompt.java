package org.pijota.agent;

import org.pijota.util.Platform;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Assembles the system prompt from the parts that describe this run: the environment, the available tools, the
 * project's own context files, and whatever extensions contribute.
 */
public final class SystemPrompt {

    private static final String BASE_PROMPT = """
                                    You are pijota, an interactive CLI tool that helps users with software engineering tasks.

                                    TONE AND STYLE:
                                    - Be concise and direct. Minimize output tokens while maintaining helpfulness.
                                    - Answer the user directly without preamble or postamble.
                                    - Follow existing code conventions when making changes.
                                    - Never assume a library is available. Check the codebase first.

                                    IMPORTANT RULES:
                                    - Never commit unless explicitly asked.
                                    - Never generate or guess URLs.
                                    """;

    /** The built-in tools the guidance below has something specific to say about, in the order it names them. */
    private static final List<String> DEDICATED = List.of("read", "edit", "write", "glob", "grep", "ls");

    /**
     * The tool half of the prompt, naming only what this run actually has. Assembled rather than fixed because the tool
     * set is not: {@code --no-builtin-tools} keeps extension tools and drops exactly the ones a fixed text names, and
     * {@code --read-only} leaves read and glob without edit or write. Advice for a tool that was never sent is an
     * instruction the model cannot carry out, so it reaches for the missing tool and writes the call as prose in its
     * own markup, which is what the reader gets instead of an answer.
     */
    private static String toolGuidance(Set<String> tools) {
        var use = new StringBuilder();
        String dedicated = DEDICATED.stream().filter(tools::contains).collect(Collectors.joining(", "));
        if (!dedicated.isEmpty() && tools.contains("bash"))
            use.append("- Prefer the dedicated tools (").append(dedicated).append(
                                            ") over their shell equivalents (cat, sed, find, grep, ls) run through bash;")
                                            .append(" they are faster and respect .gitignore.\n");
        line(use, tools, "Use bash for commands, builds, tests, and git.", "bash");
        line(use, tools, "Find files with glob (by name) and search contents with grep; don't cat or find through the tree.",
                                        "glob", "grep");
        use.append("- Make every tool call count: pass complete, valid arguments and act on the result rather than"
                                        + " re-issuing the same call.\n");

        var explore = new StringBuilder();
        line(explore, tools, "For codebase overviews, start with glob patterns like '**/*.java', '**/pom.xml',"
                                        + " or '**/package.json'.", "glob");
        line(explore, tools, "Do not walk source trees one directory at a time with repeated ls calls.", "ls", "glob");
        line(explore, tools, "Use ls only when you need the immediate contents of one specific directory.", "ls");

        var rules = new StringBuilder();
        line(rules, tools, "Read a file before editing it, and edit using the EXACT text that appears in the file.",
                                        "read", "edit");
        line(rules, tools, "Run lint/typecheck commands after making changes when possible.", "bash");
        line(rules, tools, "Create parent directories when writing new files.", "write");
        rules.append("- Use absolute paths in tool calls.\n");

        return section("TOOL USE", use) + section("EXPLORATION GUIDELINES", explore) + section("MORE RULES", rules);
    }

    /** Adds the line only when every tool it names is one the model was actually given. */
    private static void line(StringBuilder sb, Set<String> tools, String text, String... needs) {
        for (String need : needs)
            if (!tools.contains(need))
                return;
        sb.append("- ").append(text).append('\n');
    }

    /** A titled block, or "" when this run's tools left nothing in it to say. */
    private static String section(String title, StringBuilder body) {
        return body.isEmpty() ? "" : "\n" + title + ":\n" + body;
    }

    /**
     * Said when the run has no tools. Omitting the tool guidance is not enough on its own: asked something it would
     * normally shell out for, a model still reaches for a tool, and with no tool channel to put it in, it writes the
     * call as prose in its own markup (deepseek-v4-flash emits a DSML block) and that is what the reader gets instead
     * of an answer. So the absence is stated rather than left to be inferred.
     */
    private static final String NO_TOOL_PROMPT = """

                                    TOOLS:
                                    - This run has no tools: no shell, no file access, nothing to execute.
                                    - Answer from what you already know, and say plainly when something is not knowable without running a command, rather than describing what you would run.
                                    - Never write a tool call, in any syntax. Nothing can execute it, so it reaches the user as raw markup instead of an answer.
                                    """;

    private SystemPrompt() {}

    /**
     * Builds the system prompt. Tool names, descriptions and schemas are sent to the model separately in the tool
     * manifest, so they are deliberately NOT duplicated here. Optional capabilities add their own guidance through
     * extension prompt contributions. {@code toolNames} is what the model is actually being sent, so the guidance
     * describes the run it is in: empty says so outright, and otherwise only the tools that are there get named.
     */
    public static String build(String customPrompt, String appendPrompt, String cwd, String shell,
                                    List<String> extensionPrompts, Set<String> toolNames) {
        var sb = new StringBuilder(BASE_PROMPT);
        sb.append(toolNames.isEmpty() ? NO_TOOL_PROMPT : toolGuidance(toolNames));

        sb.append("\nENVIRONMENT:\n");
        sb.append("- Working directory: ").append(cwd).append('\n');
        sb.append("- Date: ").append(LocalDate.now()).append('\n');
        sb.append("- Platform: ").append(System.getProperty("os.name")).append('\n');
        sb.append("- Shell: ").append(shell != null && !shell.isBlank() ? shell : Platform.defaultShell()).append('\n');
        sb.append("- Shell commands expose PIJOTA_* environment variables with current model and session details.\n");

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
