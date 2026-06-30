package ai.pijota.tui;

import ai.pijota.config.PathDisplay;
import ai.pijota.messages.Message;
import ai.pijota.tools.Tool;
import ai.pijota.tools.ToolLabels;
import ai.pijota.theme.AnsiTheme;
import ai.pijota.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Builds the styled scrollback lines for a completed tool call: a one-line header (icon, tool name, target detail,
 * result summary, bash exit code) plus any rendered body lines. Pure given the message, the optional extension-provided
 * {@link Tool.ToolResultView}, and theming inputs — so the formatting is unit-testable, separate from the TUI's
 * stateful diff/preview handling which stays in the event loop.
 */
final class ToolResultLines {

    private ToolResultLines() {}

    static List<String> compose(Message.ToolResult msg, Tool.ToolResultView rendered, Supplier<String> args,
                                    AnsiTheme theme, String rawCwd, PathDisplay pathDisplay, long elapsedMs,
                                    String glyph) {
        String slot = msg.isError() ? "toolError" : "toolResult";
        String nameSlot = msg.isError() ? "toolError" : "toolLabel";
        String marker = msg.isError() ? "✖" : glyph; // the tool supplies its glyph; ✖ marks any failure

        // Head: marker · tool name (accent) · target detail (muted).
        var head = new StringBuilder();
        head.append(theme.styled(nameSlot, marker)).append(' ').append(theme.styled(nameSlot, msg.toolName()));
        String detail = ToolLabels.detail(msg.toolName(), args, rawCwd, pathDisplay);
        if (!detail.isBlank())
            head.append(' ').append(theme.styled("statusText", detail));

        // Trailing meta — summary, bash exit code, elapsed — set off by dim middle-dot separators for rhythm.
        var meta = new ArrayList<String>();
        String summary = rendered != null ? rendered.summary() : toolResultSummary(msg);
        if (!summary.isBlank())
            meta.add(theme.styled(slot, summary));
        if ("bash".equals(msg.toolName())) {
            Map<String, Object> details = msg.details() == null ? Map.of() : msg.details();
            if (details.get("exitCode") instanceof Number ec)
                meta.add(theme.styled(ec.intValue() == 0 ? "statusText" : "toolError", "exit " + ec.intValue()));
        }
        if (elapsedMs >= 1000) // sub-second tools are instant; only annotate the ones the user actually waits on
            meta.add(theme.styled("commandMeta", Util.formatDuration(elapsedMs)));
        if (!meta.isEmpty())
            head.append("  ").append(String.join(theme.styled("commandMeta", " · "), meta));

        var lines = new ArrayList<String>();
        lines.add(head.toString());
        if (rendered != null && !rendered.body().isEmpty())
            for (String line : rendered.body())
                lines.add(renderToolBodyLine(line, theme));
        return lines;
    }

    static String toolResultSummary(Message.ToolResult msg) {
        if (msg.isError()) {
            String content = msg.content();
            if (content == null || content.isBlank())
                return "";
            var all = content.lines().toList();
            String shown = all.stream().limit(5).reduce("", (a, b) -> a + (a.isEmpty() ? "" : "\n") + b);
            return all.size() > 5 ? shown + "\n… (+" + (all.size() - 5) + " more)" : shown;
        }
        Map<String, Object> details = msg.details() == null ? Map.of() : msg.details();
        return switch (msg.toolName()) {
            case "grep" -> details.get("matchCount") != null
                                            ? details.get("matchCount") + " match(es)"
                                            : Util.firstLine(msg.content());
            case "glob" -> details.get("resultCount") != null
                                            ? details.get("resultCount") + " result(s)"
                                            : Util.firstLine(msg.content());
            case "read" -> details.get("totalLines") != null
                                            ? details.get("totalLines") + " line(s)"
                                            : Util.firstLine(msg.content());
            case "task" -> details.get("resultCount") != null
                                            ? details.get("mode") + ": " + details.get("succeeded") + "/"
                                                                            + details.get("resultCount") + " ok"
                                            : Util.firstLine(msg.content());
            case "ls" -> details.get("count") instanceof Number n
                                            ? n.intValue() + (n.intValue() == 1 ? " entry" : " entries")
                                            : Util.firstLine(msg.content());
            default -> Util.firstLine(msg.content());
        };
    }

    static String renderToolBodyLine(String line, AnsiTheme theme) {
        // Checklist items (e.g. from the todo tool) — colour by state marker, keyed on content not tool name.
        String todoSlot = null;
        if (line.startsWith("│ [✓]"))
            todoSlot = "diffAdd";
        else if (line.startsWith("│ [~]"))
            todoSlot = "statusWarn";
        else if (line.startsWith("│ [−]"))
            todoSlot = "toolError";
        if (todoSlot != null)
            return theme.styled("statusText", "│ ") + theme.styled(todoSlot, line.substring(2));
        if (line.startsWith("╰→"))
            return theme.styled("statusText", "╰→") + theme.styled("commandMeta", line.substring(2));
        if (line.equals("┴") || line.equals("┬"))
            return theme.styled("statusText", line);
        String bodySlot = line.startsWith("⋯") ? "commandMeta" : "statusText";
        return theme.styled(bodySlot, line);
    }
}
