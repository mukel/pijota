package org.pijota.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.pijota.util.Util;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exports a Pijota session JSONL file to human-readable Markdown. Walks the active branch (following leafId) to produce
 * a clean conversation transcript with code blocks, tool calls, and metadata.
 */
public final class SessionExporter {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                    .withZone(ZoneId.systemDefault());

    private SessionExporter() {}

    public static String toMarkdown(Path sessionFile) throws IOException {
        return toMarkdown(sessionFile, null);
    }

    public static String toHtml(Path sessionFile) throws IOException {
        String md = toMarkdown(sessionFile);
        var html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        html.append("<title>Pijota Session</title>");
        html.append("<style>body{margin:0;background:#101014;color:#eee;font:16px/1.5 system-ui,sans-serif}");
        html.append("main{max-width:980px;margin:0 auto;padding:32px 20px}");
        html.append("pre{white-space:pre-wrap;background:#181820;border:1px solid #30303a;border-radius:10px;padding:18px;overflow:auto}");
        html.append("</style></head><body><main><pre>").append(escapeHtml(md)).append("</pre></main></body></html>");
        return html.toString();
    }

    /**
     * Export the active branch of a session file to Markdown, with optional leafId override (null = follow last entry).
     */
    public static String toMarkdown(Path sessionFile, String leafId) throws IOException {
        if (!Files.exists(sessionFile)) {
            throw new IOException("Session file not found: " + sessionFile);
        }

        // Read the session header (first line with type=session)
        var header = Map.<String, Object>of();
        try (var lines = Files.lines(sessionFile)) {
            var first = lines.limit(1).reduce((a, b) -> b).orElse(null);
            if (first != null) {
                try {
                    @SuppressWarnings("unchecked")
                    var h = (Map<String, Object>) Util.MAPPER.readValue(first, Map.class);
                    if ("session".equals(h.get("type")))
                        header = h;
                } catch (Exception ignored) {}
            }
        }

        var idToEntry = SessionManager.readAllEntries(sessionFile);
        if (idToEntry.isEmpty()) {
            return "# Pijota Session\n\n*(empty session)*\n";
        }

        // If no leafId specified, use the last entry in insertion order
        String effectiveLeaf = leafId;
        if (effectiveLeaf == null || !idToEntry.containsKey(effectiveLeaf)) {
            effectiveLeaf = null;
            for (var id : idToEntry.keySet())
                effectiveLeaf = id;
        }

        var branch = SessionManager.buildBranchPath(idToEntry, effectiveLeaf);

        var md = new StringBuilder();
        appendFrontMatter(md, header, sessionFile);

        int turnNumber = 0;
        var turn = new TurnAccumulator();
        for (var entry : branch) {
            String type = (String) entry.getOrDefault("type", "message");
            switch (type) {
                case "model_change" -> {} // rendered in front matter
                case "session_info" -> {} // rendered in front matter
                case "compaction" -> {
                    flushTurn(md, turn, ++turnNumber);
                    turn.reset();
                    appendCompaction(md, entry);
                }
                case "branch_summary" -> {
                    flushTurn(md, turn, ++turnNumber);
                    turn.reset();
                    appendBranchSummary(md, entry);
                }
                case "message" -> {
                    String role = (String) entry.getOrDefault("role", "");
                    if ("user".equals(role)) {
                        flushTurn(md, turn, ++turnNumber);
                        turn.reset();
                    }
                    turn.add(entry);
                }
                default -> {} // skip unknown
            }
        }
        flushTurn(md, turn, ++turnNumber);

        return md.toString();
    }

    // -- internal helpers --

    private static void appendFrontMatter(StringBuilder md, Map<String, Object> header, Path sessionFile) {
        md.append("# Pijota Session\n\n");

        String ts = (String) header.getOrDefault("timestamp", "");
        if (!ts.isBlank()) {
            try {
                Instant instant = Instant.parse(ts);
                md.append("**Exported:** ").append(DATE_FMT.format(instant)).append("\n\n");
            } catch (Exception ignored) {}
        }

        String cwd = (String) header.getOrDefault("cwd", "");
        if (!cwd.isBlank()) {
            md.append("**Working Directory:** `").append(escapeMarkdown(cwd)).append("`\n\n");
        }

        String sessionId = (String) header.getOrDefault("id", "");
        if (!sessionId.isBlank()) {
            md.append("**Session:** `").append(shortId(sessionId)).append("`\n\n");
        }

        md.append("---\n\n");
    }

    private static void appendBranchSummary(StringBuilder md, Map<String, Object> entry) {
        String summary = (String) entry.get("summary");
        if (summary == null || summary.isBlank())
            return;
        md.append("## Branch Summary\n\n");
        md.append(summary).append("\n\n---\n\n");
    }

    private static void flushTurn(StringBuilder md, TurnAccumulator turn, int turnNumber) {
        if (turn.isEmpty())
            return;

        md.append("## Turn ").append(turnNumber).append("\n\n");

        for (var entry : turn.entries) {
            String role = (String) entry.getOrDefault("role", "");
            switch (role) {
                case "user" -> appendUserMessage(md, entry);
                case "assistant" -> appendAssistantMessage(md, entry);
                case "tool_result", "tool" -> appendToolResult(md, entry);
            }
        }
        md.append("---\n\n");
    }

    private static void appendUserMessage(StringBuilder md, Map<String, Object> entry) {
        md.append("### User\n\n");
        String content = (String) entry.getOrDefault("content", "");
        if (!content.isBlank()) {
            md.append(content).append("\n\n");
        }
    }

    private static void appendAssistantMessage(StringBuilder md, Map<String, Object> entry) {
        md.append("### Assistant\n\n");

        String thinking = (String) entry.get("thinking");
        if (thinking != null && !thinking.isBlank()) {
            md.append("<details>\n<summary>Thinking</summary>\n\n");
            md.append(thinking).append("\n\n");
            md.append("</details>\n\n");
        }

        // Tool calls
        Object tcObj = entry.get("tool_calls");
        if (tcObj instanceof List<?> list && !list.isEmpty()) {
            md.append("*Tool calls:*\n\n");
            for (var item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object nameVal = m.get("name");
                    Object idVal = m.get("id");
                    String name = String.valueOf(nameVal != null ? nameVal : "?");
                    String callId = String.valueOf(idVal != null ? idVal : "?");
                    md.append("- `").append(name).append("`");
                    Object args = m.get("arguments");
                    if (args instanceof String s && !s.isBlank() && !"{}".equals(s)) {
                        md.append(" (").append(s.length() > 80 ? s.substring(0, 80) + "…" : s).append(")");
                    }
                    md.append("\n");
                }
            }
            md.append("\n");
        }

        // Assistant text content
        String content = (String) entry.get("content");
        if (content != null && !content.isBlank()) {
            md.append(content).append("\n\n");
        }
    }

    private static void appendToolResult(StringBuilder md, Map<String, Object> entry) {
        String toolName = String.valueOf(entry.getOrDefault("tool_name", "?"));
        boolean isError = Boolean.TRUE.equals(entry.get("is_error"));
        String content = (String) entry.get("content");

        md.append("### Tool: `").append(toolName).append("`");
        if (isError)
            md.append(" ⚠️ error");
        md.append("\n\n");

        @SuppressWarnings("unchecked")
        var details = (Map<String, Object>) entry.get("details");
        appendToolDetail(md, toolName, details);

        if (content != null && !content.isBlank()) {
            String lang = languageForTool(toolName);
            // Truncate very large tool outputs
            if (content.length() > 10_000) {
                md.append("```").append(lang).append("\n");
                md.append(content.substring(0, 10_000)).append("\n");
                md.append("```\n\n");
                md.append("*(output truncated — ").append(content.length()).append(" total characters)*\n\n");
            } else {
                md.append("```").append(lang).append("\n");
                md.append(content).append("\n");
                md.append("```\n\n");
            }
        }
    }

    private static void appendToolDetail(StringBuilder md, String toolName, Map<String, Object> details) {
        if (details == null || details.isEmpty())
            return;

        switch (toolName) {
            case "read" -> {
                Object totalLines = details.get("totalLines");
                Object offset = details.get("offset");
                Object limit = details.get("limit");
                if (totalLines instanceof Number lines) {
                    md.append("*").append(lines.intValue()).append(" line(s)");
                    if (offset instanceof Number o)
                        md.append(", offset ").append(o.intValue());
                    if (limit instanceof Number l)
                        md.append(", limit ").append(l.intValue());
                    md.append("*\n\n");
                }
            }
            case "grep" -> {
                Object matchCount = details.get("matchCount");
                Object pattern = details.get("pattern");
                if (matchCount instanceof Number mc) {
                    md.append("*").append(mc.intValue()).append(" match(es)");
                    if (pattern instanceof String p)
                        md.append(" for `").append(escapeMarkdown(p)).append("`");
                    md.append("*\n\n");
                }
            }
            case "glob" -> {
                Object resultCount = details.get("resultCount");
                if (resultCount instanceof Number rc) {
                    md.append("*").append(rc.intValue()).append(" result(s)*\n\n");
                }
            }
            case "write", "edit" -> {
                Object filePath = details.get("filePath");
                if (filePath instanceof String fp) {
                    md.append("**File:** `").append(escapeMarkdown(fp)).append("`\n\n");
                }
            }
            case "bash" -> {
                Object exitCode = details.get("exitCode");
                if (exitCode instanceof Number ec && ec.intValue() != 0) {
                    md.append("**Exit code:** ").append(ec.intValue()).append("\n\n");
                }
            }
            case "task" -> {
                Object mode = details.get("mode");
                Object succeeded = details.get("succeeded");
                Object resultCount = details.get("resultCount");
                if (mode instanceof String m) {
                    md.append("*Mode: ").append(m);
                    if (succeeded instanceof Number s && resultCount instanceof Number rc) {
                        md.append(", ").append(s.intValue()).append("/").append(rc.intValue()).append(" succeeded");
                    }
                    md.append("*\n\n");
                }
            }
        }
    }

    private static void appendCompaction(StringBuilder md, Map<String, Object> entry) {
        md.append("## Compaction\n\n");
        String summary = (String) entry.getOrDefault("summary", "");
        Object count = entry.get("compactedCount");
        if (count instanceof Number n) {
            md.append("*Compacted ").append(n.intValue()).append(" messages*\n\n");
        }
        if (summary != null && !summary.isBlank()) {
            md.append("> ").append(summary.replace("\n", "\n> ")).append("\n\n");
        }
        md.append("---\n\n");
    }

    private static String languageForTool(String toolName) {
        return switch (toolName) {
            case "bash" -> "bash";
            case "write", "edit" -> ""; // let the content determine language
            case "grep" -> "text";
            case "read" -> "";
            default -> "text";
        };
    }

    private static String escapeMarkdown(String s) {
        return s.replace("\\", "\\\\").replace("`", "\\`").replace("*", "\\*").replace("_", "\\_").replace("[", "\\[")
                                        .replace("]", "\\]").replace("<", "\\<").replace(">", "\\>");
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    // -- turn accumulator --

    private static class TurnAccumulator {
        final List<Map<String, Object>> entries = new ArrayList<>();

        void add(Map<String, Object> entry) {
            entries.add(entry);
        }

        void reset() {
            entries.clear();
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }
    }
}
