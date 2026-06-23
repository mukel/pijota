package ai.pijota.tui;

import ai.pijota.config.PathDisplay;
import ai.pijota.util.Util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class ToolRender {

    private ToolRender() {}

    static String trace(String toolName, Supplier<String> args, String cwd, PathDisplay pathDisplay) {
        Map<String, Object> a = parse(args);
        if (a.isEmpty())
            return "→ " + toolName;
        return switch (toolName) {
            case "read" -> "→ Read " + displayPath(pathArg(a), cwd, pathDisplay) + options(a, "offset", "limit");
            case "write" -> "→ Write " + displayPath(pathArg(a), cwd, pathDisplay);
            case "edit" -> "→ Edit " + displayPath(pathArg(a), cwd, pathDisplay);
            case "grep" -> "✱ Grep " + quote(a.get("pattern")) + " in "
                                            + displayPath(a.getOrDefault("path", ".").toString(), cwd, pathDisplay)
                                            + options(a, "glob", "context", "limit");
            case "glob" -> "✱ Glob " + quote(a.get("pattern")) + " in "
                                            + displayPath(a.getOrDefault("path", ".").toString(), cwd, pathDisplay)
                                            + options(a, "limit");
            case "ls" ->
                "→ Ls " + displayPath(a.getOrDefault("path", ".").toString(), cwd, pathDisplay) + options(a, "limit");
            case "bash" -> "→ Bash " + quote(shorten(a.getOrDefault("command", "").toString(), 80));
            case "question" -> "→ Question " + shorten(questionText(a), 80);
            case "task" -> "→ Task " + taskLabel(a);
            case "webfetch" -> "→ WebFetch " + shorten(a.getOrDefault("url", "").toString(), 80);
            case "websearch" -> "→ WebSearch " + quote(shorten(a.getOrDefault("query", "").toString(), 80));
            default -> "→ " + toolName;
        };
    }

    static String detail(String toolName, Supplier<String> args, String cwd, PathDisplay pathDisplay) {
        Map<String, Object> a = parse(args);
        if (a.isEmpty())
            return "";
        return switch (toolName) {
            case "read" -> displayPath(pathArg(a), cwd, pathDisplay) + options(a, "offset", "limit");
            case "write", "edit" -> displayPath(pathArg(a), cwd, pathDisplay);
            case "bash" -> quote(shorten(a.getOrDefault("command", "").toString(), 60));
            case "question" -> shorten(questionText(a), 60);
            case "ls" -> displayPath(a.getOrDefault("path", ".").toString(), cwd, pathDisplay) + options(a, "limit");
            case "grep" ->
                quote(a.get("pattern")) + " in " + displayPath(a.getOrDefault("path", ".").toString(), cwd, pathDisplay)
                                                + options(a, "glob", "context", "limit");
            case "glob" ->
                quote(a.get("pattern")) + " in " + displayPath(a.getOrDefault("path", ".").toString(), cwd, pathDisplay)
                                                + options(a, "limit");
            case "task" -> taskDetail(a);
            case "webfetch" -> shorten(a.getOrDefault("url", "").toString(), 60);
            case "websearch" -> quote(shorten(a.getOrDefault("query", "").toString(), 60));
            default -> "";
        };
    }

    static String pathArg(Map<String, Object> args) {
        Object path = args.get("path");
        if (path == null)
            path = args.get("filePath");
        if (path == null)
            path = args.get("file_path");
        return path == null ? "" : path.toString();
    }

    private static Map<String, Object> parse(Supplier<String> args) {
        if (args == null)
            return Map.of();
        return Util.parseJsonArgs(args.get());
    }

    /**
     * Render a path per the configured {@link PathDisplay} mode: FULL (absolute), RELATIVE (to cwd, or absolute/{@code
     * ~} when outside), COMPACT (relative with each intermediate dir shrunk to its first letter, e.g.
     * {@code s/m/j/util/File.java}), or NAME (filename only).
     */
    static String displayPath(String path, String cwd, PathDisplay mode) {
        if (path == null || path.isBlank())
            return ".";
        return switch (mode == null ? PathDisplay.COMPACT : mode) {
            case NAME -> fileName(path);
            case FULL -> absolutePath(path, cwd);
            case COMPACT -> compact(displayPath(path, cwd));
            case RELATIVE -> displayPath(path, cwd);
        };
    }

    private static String absolutePath(String path, String cwd) {
        try {
            Path p = Path.of(path);
            if (!p.isAbsolute())
                p = normalizePath(cleanCwd(cwd)).resolve(p);
            return p.toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return path;
        }
    }

    // The cwd may carry a trailing annotation like " (git)"; strip it once (cached pattern, not recompiled per call).
    private static final java.util.regex.Pattern CWD_ANNOTATION = java.util.regex.Pattern.compile("\\(.*\\)");

    private static String cleanCwd(String cwd) {
        return CWD_ANNOTATION.matcher(cwd).replaceAll("").strip();
    }

    private static String compact(String path) {
        int lastSep = path.lastIndexOf('/');
        if (lastSep <= 0)
            return path; // a bare filename, "." or "~" — nothing to compact
        var sb = new StringBuilder();
        for (String seg : path.substring(0, lastSep).split("/", -1))
            sb.append(abbrev(seg)).append('/');
        return sb.append(path.substring(lastSep + 1)).toString();
    }

    /** First character of a path segment, preserving "", ".", "..", "~" and a leading dot for hidden dirs. */
    private static String abbrev(String seg) {
        if (seg.isEmpty() || seg.equals(".") || seg.equals("..") || seg.equals("~"))
            return seg;
        if (seg.startsWith(".") && seg.length() > 1)
            return seg.substring(0, 2);
        return seg.substring(0, 1);
    }

    static String displayPath(String path, String cwd) {
        if (path == null || path.isBlank())
            return ".";
        try {
            Path base = normalizePath(cleanCwd(cwd));
            Path resolved = Path.of(path);
            if (!resolved.isAbsolute())
                resolved = base.resolve(resolved);
            resolved = resolved.toAbsolutePath().normalize();
            if (resolved.startsWith(base)) {
                Path rel = base.relativize(resolved);
                String s = rel.toString();
                return s.isEmpty() ? "." : s;
            }
        } catch (Exception ignored) {}
        String home = System.getProperty("user.home");
        if (home == null || !path.startsWith(home))
            return path;
        if (path.length() == home.length())
            return "~";
        if (path.charAt(home.length()) == '/')
            return "~" + path.substring(home.length());
        return path;
    }

    private static Path normalizePath(String path) {
        String home = System.getProperty("user.home");
        if (home != null && (path.equals("~") || path.startsWith("~/"))) {
            path = home + path.substring(1);
        }
        return Path.of(path).toAbsolutePath().normalize();
    }

    static String fileName(String path) {
        if (path == null || path.isBlank())
            return "";
        Path p = Path.of(path);
        return p.getFileName() != null ? p.getFileName().toString() : path;
    }

    static String options(Map<String, Object> args, String... keys) {
        var parts = new ArrayList<String>();
        for (String key : keys) {
            Object value = args.get(key);
            if (value != null && (!(value instanceof String s) || !s.isBlank()))
                parts.add(key + "=" + value);
        }
        return parts.isEmpty() ? "" : " [" + String.join(", ", parts) + "]";
    }

    static String quote(Object value) {
        return "\"" + oneLine(value == null ? "" : value.toString()) + "\"";
    }

    static String oneLine(String value) {
        return Util.stripSystemReminders(value).replaceAll("\\R+", " ").strip();
    }

    static String questionText(Map<String, Object> args) {
        Object q = args.get("question") != null ? args.get("question") : args.get("text");
        return q != null ? q.toString() : String.valueOf(args.getOrDefault("questions", ""));
    }

    static String taskLabel(Map<String, Object> args) {
        if (args.containsKey("chain") && args.get("chain") instanceof List<?> c)
            return "chain (" + c.size() + " steps)";
        if (args.containsKey("tasks") && args.get("tasks") instanceof List<?> t)
            return "parallel (" + t.size() + " tasks)";
        return shorten(args.getOrDefault("agent", "").toString(), 40);
    }

    static String taskDetail(Map<String, Object> args) {
        if (args.containsKey("chain") && args.get("chain") instanceof List<?> c)
            return c.size() + " steps";
        if (args.containsKey("tasks") && args.get("tasks") instanceof List<?> t)
            return t.size() + " tasks";
        return shorten(args.getOrDefault("agent", "").toString(), 30);
    }

    static String shorten(String s, int max) {
        return Util.truncateWords(s, max);
    }
}
