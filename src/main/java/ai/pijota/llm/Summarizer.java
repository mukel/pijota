package ai.pijota.llm;

import ai.pijota.messages.Message;
import ai.pijota.util.Util;
import java.util.List;

final class Summarizer {
    private Summarizer() {}

    static String buildSummaryPrompt(List<Message> toSummarize, String instructions, String previousSummary) {
        var sb = new StringBuilder();
        sb.append("<conversation>\n");
        for (var msg : toSummarize) {
            if (msg instanceof Message.User u) {
                sb.append("[User]: ").append(Util.truncate(u.content(), 4000)).append("\n\n");
            } else if (msg instanceof Message.Assistant a) {
                if (a.thinking() != null && !a.thinking().isBlank()) {
                    sb.append("[Assistant thinking]: ").append(Util.truncate(a.thinking(), 1000)).append("\n\n");
                }
                sb.append("[Assistant]: ").append(Util.truncate(
                    a.content() != null ? a.content() : "", 4000)).append("\n\n");
                if (a.toolCalls() != null && !a.toolCalls().isEmpty()) {
                    sb.append("[Assistant tool calls]: ");
                    for (var tc : a.toolCalls()) {
                        sb.append(tc.name()).append('(').append(Util.truncate(tc.arguments(), 1000)).append("), ");
                    }
                    sb.setLength(Math.max(0, sb.length() - 2));
                    sb.append("\n\n");
                }
            } else if (msg instanceof Message.ToolResult tr) {
                sb.append("[Tool result: ").append(tr.toolName())
                    .append(tr.isError() ? " error" : "").append("]: ")
                    .append(Util.truncate(tr.content(), 2000)).append("\n\n");
            } else if (msg instanceof Message.CompactionSummary cs) {
                sb.append("[Previous conversation summary]: ").append(cs.summary()).append("\n\n");
            }
        }
        sb.append("</conversation>\n\n");

        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append("<previous-summary>\n").append(previousSummary).append("\n</previous-summary>\n\n");
            sb.append("Update the existing structured summary with the new conversation. Preserve important existing information, add new progress, and remove obsolete blockers.\n\n");
        } else {
            sb.append("Create a structured context checkpoint summary that another coding agent will use to continue the work.\n\n");
        }
        if (instructions != null && !instructions.isBlank()) {
            sb.append("Additional focus: ").append(instructions).append("\n\n");
        }
        sb.append("Use this exact format:\n\n")
            .append("## Goal\n[What the user is trying to accomplish.]\n\n")
            .append("## Constraints & Preferences\n- [Requirements, preferences, or constraints.]\n\n")
            .append("## Progress\n### Done\n- [Completed work.]\n\n")
            .append("### In Progress\n- [Current work, or (none).]\n\n")
            .append("### Blocked\n- [Blockers, or (none).]\n\n")
            .append("## Key Decisions\n- **[Decision]**: [Brief rationale.]\n\n")
            .append("## Next Steps\n1. [What should happen next.]\n\n")
            .append("## Critical Context\n- [Exact file paths, function names, errors, commands, and data needed to continue.]\n\n")
            .append("Keep each section concise. Preserve exact file paths, function names, commands, and error messages.");
        return sb.toString();
    }
}
