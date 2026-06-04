package ai.pijota.tui;

import ai.pijota.util.Util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class ToolRender {

    private ToolRender() {}

    static String trace(String toolName, Supplier<String> args, String cwd, boolean shortenPaths) {
        Map<String, Object> a = parse(args);
        if (a.isEmpty()) return "→ " + toolName;
        return switch (toolName) {
            case "read" -> "→ Read " + displayPath(pathArg(a), cwd, shortenPaths) + options(a, "offset", "limit");
            case "write" -> "→ Write " + displayPath(pathArg(a), cwd, shortenPaths);
            case "edit" -> "→ Edit " + displayPath(pathArg(a), cwd, shortenPaths);
            case "grep" -> "✱ Grep " + quote(a.get("pattern")) + " in "
                + displayPath(a.getOrDefault("path", ".").toString(), cwd, shortenPaths)
                + options(a, "glob", "context", "limit");
            case "glob" -> "✱ Glob " + quote(a.get("pattern")) + " in "
                + displayPath(a.getOrDefault("path", ".").toString(), cwd, shortenPaths)
                + options(a, "limit");
            case "ls" -> "→ Ls " + displayPath(a.getOrDefault("path", ".").toString(), cwd, shortenPaths)
                + options(a, "limit");
            case "bash" -> "→ Bash " + quote(shorten(a.getOrDefault("command", "").toString(), 80));
            case "question" -> "→ Question " + shorten(questionText(a), 80);
            case "task" -> "→ Task " + taskLabel(a);
            case "webfetch" -> "→ WebFetch " + shorten(a.getOrDefault("url", "").toString(), 80);
            case "websearch" -> "→ WebSearch " + quote(shorten(a.getOrDefault("query", "").toString(), 80));
            case "todowrite" -> "→ TodoWrite " + todoCount(a);
            default -> "→ " + toolName;
        };
    }

    static String detail(String toolName, Supplier<String> args, String cwd, boolean shortenPaths) {
        Map<String, Object> a = parse(args);
        if (a.isEmpty()) return "";
        return switch (toolName) {
            case "read" -> displayPath(pathArg(a), cwd, shortenPaths) + options(a, "offset", "limit");
            case "write", "edit" -> displayPath(pathArg(a), cwd, shortenPaths);
            case "bash" -> quote(shorten(a.getOrDefault("command", "").toString(), 60));
            case "question" -> shorten(questionText(a), 60);
            case "ls" -> displayPath(a.getOrDefault("path", ".").toString(), cwd, shortenPaths)
                + options(a, "limit");
            case "grep" -> quote(a.get("pattern")) + " in "
                + displayPath(a.getOrDefault("path", ".").toString(), cwd, shortenPaths)
                + options(a, "glob", "context", "limit");
            case "glob" -> quote(a.get("pattern")) + " in "
                + displayPath(a.getOrDefault("path", ".").toString(), cwd, shortenPaths)
                + options(a, "limit");
            case "task" -> taskDetail(a);
            case "webfetch" -> shorten(a.getOrDefault("url", "").toString(), 60);
            case "websearch" -> quote(shorten(a.getOrDefault("query", "").toString(), 60));
            default -> "";
        };
    }

    static String pathArg(Map<String, Object> args) {
        Object path = args.get("path");
        if (path == null) path = args.get("filePath");
        if (path == null) path = args.get("file_path");
        return path == null ? "" : path.toString();
    }

    private static Map<String, Object> parse(Supplier<String> args) {
        if (args == null) return Map.of();
        return Util.parseJsonArgs(args.get());
    }

    private static String displayPath(String path, String cwd, boolean shorten) {
        String result = displayPath(path, cwd);
        if (shorten && result != null && !result.isBlank() && !result.equals(".")) {
            Path p = Path.of(result);
            Path name = p.getFileName();
            if (name != null && p.getNameCount() > 1) {
                Path parent = p.getParent();
                if (parent != null && !parent.toString().isEmpty() && !parent.toString().equals(".")) {
                    return parent.getFileName() + "/" + name;
                }
            }
            return result;
        }
        return result;
    }

    private static String displayPath(String path, String cwd) {
        if (path == null || path.isBlank()) return ".";
        try {
            Path base = Path.of(cwd.replaceAll("\\(.*\\)", "").strip()).toAbsolutePath().normalize();
            Path resolved = Path.of(path);
            if (!resolved.isAbsolute()) resolved = base.resolve(resolved);
            resolved = resolved.toAbsolutePath().normalize();
            if (resolved.startsWith(base)) {
                Path rel = base.relativize(resolved);
                String s = rel.toString();
                return s.isEmpty() ? "." : s;
            }
        } catch (Exception ignored) {}
        String home = System.getProperty("user.home");
        return home != null && path.startsWith(home) ? "~" + path.substring(home.length()) : path;
    }

    private static String fileName(String path) {
        if (path == null || path.isBlank()) return "";
        Path p = Path.of(path);
        return p.getFileName() != null ? p.getFileName().toString() : path;
    }

    private static String options(Map<String, Object> args, String... keys) {
        var parts = new ArrayList<String>();
        for (String key : keys) {
            Object value = args.get(key);
            if (value != null && (!(value instanceof String s) || !s.isBlank())) parts.add(key + "=" + value);
        }
        return parts.isEmpty() ? "" : " [" + String.join(", ", parts) + "]";
    }

    private static String quote(Object value) {
        return "\"" + oneLine(value == null ? "" : value.toString()) + "\"";
    }

    private static String oneLine(String value) {
        return Util.stripSystemReminders(value).replaceAll("\\R+", " ").strip();
    }

    private static String questionText(Map<String, Object> args) {
        Object q = args.get("question") != null ? args.get("question") : args.get("text");
        return q != null ? q.toString() : String.valueOf(args.getOrDefault("questions", ""));
    }

    private static String taskLabel(Map<String, Object> args) {
        if (args.containsKey("chain") && args.get("chain") instanceof List<?> c)
            return "chain (" + c.size() + " steps)";
        if (args.containsKey("tasks") && args.get("tasks") instanceof List<?> t)
            return "parallel (" + t.size() + " tasks)";
        return shorten(args.getOrDefault("agent", "").toString(), 40);
    }

    private static String taskDetail(Map<String, Object> args) {
        if (args.containsKey("chain") && args.get("chain") instanceof List<?> c)
            return c.size() + " steps";
        if (args.containsKey("tasks") && args.get("tasks") instanceof List<?> t)
            return t.size() + " tasks";
        return shorten(args.getOrDefault("agent", "").toString(), 30);
    }

    private static String todoCount(Map<String, Object> args) {
        Object todos = args.get("todos");
        if (todos instanceof List<?> list) {
            long done = list.stream().filter(i -> i instanceof Map<?,?> m
                && "completed".equals(m.get("status"))).count();
            return "(" + list.size() + " items, " + done + " done)";
        }
        return "";
    }

    private static String shorten(String s, int max) {
        return Util.truncateWords(s, max);
    }
}
