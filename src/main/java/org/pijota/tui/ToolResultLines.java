package org.pijota.tui;

import org.pijota.config.PathDisplay;
import org.pijota.messages.Message;
import org.pijota.tools.Tool;
import org.pijota.tools.ToolLabels;
import org.pijota.theme.AnsiTheme;
import org.pijota.util.Util;

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

        // Name (accent) · target detail (muted). The leading marker / frame glyph is added per-layout below.
        var nameDetail = new StringBuilder(theme.styled(nameSlot, msg.toolName()));
        String detail = ToolLabels.detail(msg.toolName(), args, rawCwd, pathDisplay);
        if (!detail.isBlank())
            nameDetail.append(' ').append(theme.styled("statusText", detail));

        // Trailing meta — summary, bash exit code, elapsed. Plain values; each layout styles them (bright vs dim)
        // below.
        String summary = rendered != null ? rendered.summary() : toolResultSummary(msg);
        Integer exit = null;
        if ("bash".equals(msg.toolName())) {
            Map<String, Object> details = msg.details() == null ? Map.of() : msg.details();
            if (details.get("exitCode") instanceof Number ec)
                exit = ec.intValue();
        }
        String elapsedStr = elapsedMs >= 1000 ? Util.formatDuration(elapsedMs) : null; // sub-second tools are instant
        String dot = theme.styled("commandMeta", " · ");

        // Framed layout for bash-with-output: a bright ┌ opens with the command, bare body, and a dim └ closing line —
        // the same idiom as the thinking block (bright open, muted close). Every other tool keeps the compact header.
        var lines = new ArrayList<String>();
        if ("bash".equals(msg.toolName()) && rendered != null && !rendered.body().isEmpty()) {
            lines.add(theme.styled(nameSlot, "┌ ") + nameDetail);
            for (String line : rendered.body())
                lines.add(renderToolBodyLine(line, theme));
            var foot = new ArrayList<String>();
            if (!summary.isBlank())
                foot.add(theme.styled("commandMeta", summary));
            if (exit != null) // the exit code stays red on failure — the one cue that survives the dim closing line
                foot.add(theme.styled(exit == 0 ? "commandMeta" : "toolError", "exit " + exit));
            if (elapsedStr != null)
                foot.add(theme.styled("commandMeta", elapsedStr));
            lines.add(theme.styled("commandMeta", "└") + (foot.isEmpty() ? "" : " " + String.join(dot, foot)));
        } else {
            var meta = new ArrayList<String>();
            if (!summary.isBlank())
                meta.add(theme.styled(slot, summary));
            if (exit != null)
                meta.add(theme.styled(exit == 0 ? "statusText" : "toolError", "exit " + exit));
            if (elapsedStr != null)
                meta.add(theme.styled("commandMeta", elapsedStr));
            lines.add(theme.styled(nameSlot, marker) + " " + nameDetail
                                            + (meta.isEmpty() ? "" : "  " + String.join(dot, meta)));
            if (rendered != null && !rendered.body().isEmpty())
                for (String line : rendered.body())
                    lines.add(renderToolBodyLine(line, theme));
        }
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
        if (line.startsWith("⋯")) // truncation cue — colored so the "N lines omitted" break stands out from the output
            return theme.styled("statusWarn", line);
        return theme.styled("statusText", line);
    }
}
