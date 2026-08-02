package org.pijota.tui;

import org.pijota.config.PathDisplay;
import org.pijota.messages.Message;
import org.pijota.tools.Tool;
import org.pijota.tools.ToolGlyphs;
import org.pijota.tools.ToolLabels;
import org.pijota.theme.AnsiTheme;
import org.pijota.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Builds the styled scrollback lines for a completed tool call in the machine-block grammar: a one-line header (marker,
 * accent name, muted target, dim metadata), an optional body behind a dim {@code │} rail, and a dim {@code └} footer
 * only when a body was shown. Failures keep their metadata in the header, red on the failing token only, with the error
 * excerpt railed like any other body. Pure given the message, the optional extension-provided
 * {@link Tool.ToolResultView}, and theming inputs, so the formatting is unit-testable, separate from the TUI's stateful
 * diff/preview handling which stays in the event loop.
 */
final class ToolResultLines {

    /** Raw error-content lines shown before the {@code ⋯ N more lines} cue (tool-rendered views are never cut). */
    private static final int ERROR_EXCERPT_LINES = 5;

    private ToolResultLines() {}

    /**
     * The name a tool goes by in the transcript. Bash shows a shell {@code $}: its command <em>is</em> the content, so
     * the word "bash" in front of it is noise. Shared with the live tail and the interrupted line, which each said it
     * their own way -- one call could appear as {@code $ sleep 60} while running and {@code bash sleep 60} once it was
     * interrupted.
     */
    static String label(String toolName) {
        return "bash".equals(toolName) ? "$" : toolName;
    }

    /**
     * What a tool line opens with before its name: the tool's glyph, or nothing when the glyph and the name are already
     * the same character. Bash's run mark and its shell prompt are both {@code $}, so printing both read as
     * {@code $ $ sleep 40}.
     */
    static String glyphPrefix(String glyph, String toolName) {
        return glyph.equals(label(toolName)) ? "" : glyph + " ";
    }

    /** The result's own details, shaped like the request arguments so {@link ToolLabels} can read either. */
    private static Supplier<String> detailsAsArgs(Message.ToolResult msg) {
        return () -> {
            try {
                return Util.MAPPER.writeValueAsString(msg.details() == null ? Map.of() : msg.details());
            } catch (Exception e) {
                return "";
            }
        };
    }

    static List<String> compose(Message.ToolResult msg, Tool.ToolResultView rendered, Supplier<String> args,
                                    AnsiTheme theme, String rawCwd, PathDisplay pathDisplay, long elapsedMs,
                                    String glyph, int width) {
        // Name (accent) · target detail (muted), identical in every layout; only the marker before it varies. Bash is
        // the exception: its name is noise (the command IS the content), so it shows a shell "$" prompt instead of the
        // word "bash", and its command renders in the readable text tone, not the muted target tone, so emphasis
        // lands where you actually read.
        boolean shell = msg.toolName().equals("bash");
        var nameDetail = new StringBuilder(theme.styled("toolLabel", label(msg.toolName())));
        String detail = ToolLabels.detail(msg.toolName(), args, rawCwd, pathDisplay, ToolLabels.FULL_COMMAND);
        if (detail.isBlank())
            // An interrupt clears the in-flight call before its result lands, taking the request arguments with it,
            // and the line then said only "✖ $ · exit 143" -- a failure with no way to tell which command failed.
            // The result carries its own details (bash records the command it ran), which name it just as well.
            detail = ToolLabels.detail(msg.toolName(), detailsAsArgs(msg), rawCwd, pathDisplay,
                                            ToolLabels.FULL_COMMAND);
        if (!detail.isBlank())
            nameDetail.append(' ').append(theme.styled(shell ? "text" : "statusText", detail));

        String summary = rendered != null ? rendered.summary() : msg.isError() ? "" : toolResultSummary(msg);
        Integer exit = null;
        Map<String, Object> details = msg.details() == null ? Map.of() : msg.details();
        if (details.get("exitCode") instanceof Number ec)
            exit = ec.intValue();
        String elapsedStr = elapsedMs >= 1000 ? Util.formatDuration(elapsedMs) : null; // sub-second tools are instant
        String dot = theme.styled("commandMeta", " · ");

        var lines = new ArrayList<String>();
        if (msg.isError()) {
            // Failure: one-line header, red on the ✖ and the exit code only, then the excerpt railed like any other
            // body, so errors keep the block grammar instead of dumping multi-line content into the header.
            var meta = new ArrayList<String>();
            if (exit != null)
                meta.add(theme.styled("toolError", "exit " + exit));
            if (elapsedStr != null)
                meta.add(theme.styled("commandMeta", elapsedStr));
            lines.add(""); // framed blocks get one blank line of air (the coordinator collapses doubles)
            addHeader(lines, theme.styled("toolError", ToolGlyphs.FAILED) + " ",
                                            nameDetail + (meta.isEmpty() ? "" : dot + String.join(dot, meta)), theme,
                                            width);
            int beforeBody = lines.size();
            addBody(lines, errorBody(msg, rendered), theme, width);
            if (lines.size() > beforeBody) // the ✖ opened the block in place of ┌, so it still needs closing
                lines.add(theme.styled("commandMeta", "└"));
        } else if (rendered != null && !rendered.body().isEmpty()) {
            // Tool with output: the framed block. Accent ┌ open, railed body, dim └ footer carrying the metadata.
            lines.add("");
            addHeader(lines, theme.styled("toolLabel", "┌ "), nameDetail.toString(), theme, width);
            addBody(lines, rendered.body(), theme, width);
            var foot = metaChips(summary, exit, elapsedStr, theme);
            // The footer wraps too. Its summary is the tool's first output line, which is not length-capped, so a
            // single long line of output produced a footer that escaped the block it was supposed to close.
            lines.addAll(AnsiWidth.wrapPrefixed(foot.isEmpty() ? "" : String.join(dot, foot), width,
                                            theme.styled("commandMeta", "└") + (foot.isEmpty() ? "" : " "),
                                            row -> "  " + row));
        } else {
            // Quiet tool: the header line is the whole block, metadata uniformly dim, stacked tight with siblings.
            var meta = metaChips(summary, exit, elapsedStr, theme);
            String open = glyphPrefix(glyph, msg.toolName());
            addHeader(lines, open.isEmpty() ? "" : theme.styled("toolLabel", open),
                                            nameDetail + (meta.isEmpty() ? "" : dot + String.join(dot, meta)), theme,
                                            width);
        }
        return lines;
    }

    /**
     * The trailing metadata chips (summary, exit code, elapsed) shared verbatim by the quiet header and the framed
     * footer. A zero exit stays dim; a non-zero one is the single cue that survives red into the closing line.
     */
    private static List<String> metaChips(String summary, Integer exit, String elapsedStr, AnsiTheme theme) {
        var meta = new ArrayList<String>();
        if (!summary.isBlank())
            meta.add(theme.styled("commandMeta", summary));
        if (exit != null)
            meta.add(theme.styled(exit == 0 ? "commandMeta" : "toolError", "exit " + exit));
        if (elapsedStr != null)
            meta.add(theme.styled("commandMeta", elapsedStr));
        return meta;
    }

    /**
     * Rails a block body. Each logical line is wrapped to the railed width FIRST, so a line longer than the terminal
     * gets the {@code │} on every physical row. Otherwise the terminal wraps it itself and the continuation rows dangle
     * bare at column zero, visually breaking the block open.
     */
    private static void addBody(List<String> lines, List<String> rawBody, AnsiTheme theme, int width) {
        // Blank edges become bare rail rows -- a │ with nothing on it, which reads as the block having lost its
        // content. Trimmed here rather than at each source, since it is the rail that makes them wrong.
        var body = trimBlankEdges(rawBody);
        boolean diff = looksLikeUnifiedDiff(body);
        for (String line : body)
            for (String row : AnsiWidth.wrapPreservingAnsi(line, Math.max(1, width - 2)))
                lines.add(renderToolBodyLine(row, theme, diff));
    }

    /**
     * Whether a body is a unified diff, decided once for the whole block rather than line by line. Per-line was the
     * obvious rule and the wrong one: {@code -} and {@code +} start plenty of ordinary output, so every
     * {@code -rw-r--r--} row of an {@code ls -l} came out in error red and every {@code +} row in success green. A diff
     * always announces itself with a hunk header, and nothing else does. The {@code ---}/{@code +++} file headers are
     * not enough on their own: those shapes do turn up in ordinary output, {@code @@ -a,b +c,d @@} does not.
     */
    private static boolean looksLikeUnifiedDiff(List<String> body) {
        return body.stream().anyMatch(line -> line.startsWith("@@ "));
    }

    /**
     * A header line, wrapped to the block's width rather than left to the terminal. A long shell command overflowed to
     * column zero, so its continuation sat outside the frame and broke the box open exactly where the command is
     * hardest to read. Continuations carry the same {@code │} rail as the body, so the whole block reads as one thing.
     */
    private static void addHeader(List<String> lines, String prefix, String text, AnsiTheme theme, int width) {
        lines.addAll(AnsiWidth.wrapPrefixed(text, width, prefix, row -> renderToolBodyLine(row, theme, false)));
    }

    /**
     * Body lines for a failed call: a tool-rendered view is trusted as-is (the tool curated it); raw error content is
     * cut to {@value #ERROR_EXCERPT_LINES} lines with an amber {@code ⋯ N more lines} cue.
     */
    private static List<String> errorBody(Message.ToolResult msg, Tool.ToolResultView rendered) {
        if (rendered != null && !rendered.body().isEmpty())
            return rendered.body();
        String content = msg.content();
        if (content == null || content.isBlank())
            return List.of();
        var all = trimBlankEdges(content.lines().toList());
        if (all.size() <= ERROR_EXCERPT_LINES)
            return all;
        var cut = new ArrayList<>(all.subList(0, ERROR_EXCERPT_LINES));
        cut.add("⋯ " + Util.count(all.size() - ERROR_EXCERPT_LINES, "more line", "more lines"));
        return cut;
    }

    private static List<String> trimBlankEdges(List<String> lines) {
        int from = 0, to = lines.size();
        while (from < to && lines.get(from).isBlank())
            from++;
        while (to > from && lines.get(to - 1).isBlank())
            to--;
        return lines.subList(from, to);
    }

    static String toolResultSummary(Message.ToolResult msg) {
        Map<String, Object> details = msg.details() == null ? Map.of() : msg.details();
        return switch (msg.toolName()) {
            case "grep" -> details.get("matchCount") instanceof Number n
                                            ? Util.count(n.longValue(), "match", "matches")
                                            : Util.firstLine(msg.content());
            case "glob" -> details.get("resultCount") instanceof Number n
                                            ? Util.count(n.longValue(), "result", "results")
                                            : Util.firstLine(msg.content());
            case "read" -> details.get("totalLines") instanceof Number n
                                            ? Util.count(n.longValue(), "line", "lines")
                                            : Util.firstLine(msg.content());
            case "task" -> details.get("resultCount") != null
                                            ? details.get("mode") + ": " + details.get("succeeded") + "/"
                                                                            + details.get("resultCount") + " ok"
                                            : Util.firstLine(msg.content());
            case "ls" -> details.get("count") instanceof Number n
                                            ? Util.count(n.longValue(), "entry", "entries")
                                            : Util.firstLine(msg.content());
            default -> summaryLine(msg.content());
        };
    }

    /**
     * First line of the content, except pretty-printed JSON (common from MCP tools) whose first line is a lone brace:
     * collapse it so the summary shows actual content instead of "{".
     */
    private static String summaryLine(String content) {
        String first = Util.firstLine(content);
        if (!"{".equals(first) && !"[".equals(first))
            return first;
        return Util.ellipsize(content.replaceAll("\\s+", " "), 80);
    }

    /** A body line that is not part of a diff; see {@link #renderToolBodyLine(String, AnsiTheme, boolean)}. */
    static String renderToolBodyLine(String line, AnsiTheme theme) {
        return renderToolBodyLine(line, theme, false);
    }

    static String renderToolBodyLine(String line, AnsiTheme theme, boolean diff) {
        String rail = theme.styled("commandMeta", "│ ");
        // Checklist items (e.g. from the todo tool), coloured by state marker, keyed on content not tool name. They
        // carry their own rail already.
        String todoSlot = null;
        if (line.startsWith("│ [✓]"))
            todoSlot = "diffAdd";
        else if (line.startsWith("│ [~]"))
            todoSlot = "statusWarn";
        else if (line.startsWith("│ [−]"))
            todoSlot = "toolError";
        if (todoSlot != null)
            return theme.styled("commandMeta", "│ ") + theme.styled(todoSlot, line.substring(2));
        if (line.startsWith("╰→"))
            return theme.styled("statusText", "╰→") + theme.styled("commandMeta", line.substring(2));
        if (line.equals("┴") || line.equals("┬"))
            return theme.styled("statusText", line);
        if (line.startsWith("⋯")) // truncation cue, colored so the "N lines omitted" break stands out from the output
            return rail + theme.styled("statusWarn", line);
        // Unified-diff body (apply_patch): color the change markers so a patch reads like a diff, not like prose. Only
        // inside a body the block already recognised as a diff -- see looksLikeUnifiedDiff.
        if (diff) {
            if (line.startsWith("@@") || line.startsWith("+++") || line.startsWith("---"))
                return rail + theme.styled("diffHunk", line);
            if (line.startsWith("+"))
                return rail + theme.styled("diffAdd", line);
            if (line.startsWith("-"))
                return rail + theme.styled("diffDelete", line);
        }
        return rail + theme.styled("statusText", line);
    }
}
