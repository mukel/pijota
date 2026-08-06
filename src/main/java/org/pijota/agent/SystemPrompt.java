package org.pijota.agent;

import org.pijota.util.Platform;

import java.time.LocalDate;
import java.util.List;

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

    /**
     * The half of the prompt that only makes sense when the run actually has tools. Kept separate because a prompt
     * naming read, glob and bash to a model that was sent no tool manifest is an instruction it cannot carry out: it
     * answers by writing a tool call as prose, in whatever markup its own template uses, and that reaches the reader.
     */
    private static final String TOOL_PROMPT = """

                                    TOOL USE:
                                    - Prefer the dedicated tools (read, edit, write, glob, grep, ls) over their shell equivalents (cat, sed, find, grep, ls) run through bash; they are faster and respect .gitignore. Use bash for commands, builds, tests, and git.
                                    - Find files with glob (by name) and search contents with grep; don't cat or find through the tree.
                                    - Make every tool call count: pass complete, valid arguments and act on the result rather than re-issuing the same call.

                                    EXPLORATION GUIDELINES:
                                    - For codebase overviews, start with glob patterns like '**/*.java', '**/pom.xml', or '**/package.json'.
                                    - Do not walk source trees one directory at a time with repeated ls calls.
                                    - Use ls only when you need the immediate contents of one specific directory.

                                    MORE RULES:
                                    - Read a file before editing it, and edit using the EXACT text that appears in the file.
                                    - Run lint/typecheck commands after making changes when possible.
                                    - Create parent directories when writing new files; use absolute paths in tool calls.
                                    """;

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
     * extension prompt contributions. {@code hasTools} says whether this run has any: with none (--no-tools,
     * --read-only stripped bare) the tool half is left out, so the prompt describes the run the model is actually in.
     */
    public static String build(String customPrompt, String appendPrompt, String cwd, String shell,
                                    List<String> extensionPrompts, boolean hasTools) {
        var sb = new StringBuilder(BASE_PROMPT);
        sb.append(hasTools ? TOOL_PROMPT : NO_TOOL_PROMPT);

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
