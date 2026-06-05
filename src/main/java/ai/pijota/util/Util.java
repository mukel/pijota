package ai.pijota.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class Util {
    public static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static final int DEFAULT_MAX_CHARS = 100_000;

    private Util() {}


    public static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    public static String truncateWords(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return truncate(s, maxLen);
        int cut = maxLen - 3;
        if (cut <= 0) return s.substring(0, maxLen) + "...";
        int lastSpace = s.lastIndexOf(' ', cut);
        if (lastSpace > cut / 2) return s.substring(0, lastSpace) + "...";
        return s.substring(0, cut) + "...";
    }

    public static String escapeJson(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public static int getInt(Map<String, Object> args, String key, int defaultVal) {
        Object val = args.get(key);
        return val instanceof Number n ? n.intValue() : defaultVal;
    }

    public static long getLong(Map<String, Object> args, String key, long defaultVal) {
        Object val = args.get(key);
        return val instanceof Number n ? n.longValue() : defaultVal;
    }

    public static String getString(Map<String, Object> args, String key) {
        return getString(args, key, null);
    }

    public static String getString(Map<String, Object> args, String key, String defaultVal) {
        Object val = args.get(key);
        return val instanceof String s && !s.isBlank() ? s : defaultVal;
    }

    public static String getFirstString(Map<String, Object> args, String... keys) {
        for (String key : keys) {
            String val = getString(args, key, null);
            if (val != null) return val;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJsonArgs(String jsonArgs) {
        try {
            return MAPPER.readValue(jsonArgs, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    public static String coalesce(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    public static String stripSystemReminders(String text) {
        if (text == null || text.isBlank()) return "";
        return text.replaceAll("(?s)<system-reminder>.*?</system-reminder>", "");
    }

    public static java.nio.file.Path resolveSearchPath(java.nio.file.Path base, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return base.toAbsolutePath().normalize();
        java.nio.file.Path path = java.nio.file.Path.of(rawPath);
        return path.isAbsolute() ? path.normalize() : base.resolve(path).normalize();
    }

    public static int countOccurrences(String haystack, String needle) {
        if (needle == null || needle.isEmpty() || haystack == null) return 0;
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) { count++; idx += needle.length(); }
        return count;
    }

    public static String resolvePath(Map<String, Object> args) {
        Object v = args.get("path");
        if (v instanceof String s && !s.isBlank()) return s;
        v = args.get("filePath");
        if (v instanceof String s && !s.isBlank()) return s;
        v = args.get("file_path");
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    public static String resolvePath(Map<String, Object> args, String cwd) {
        String path = resolvePath(args);
        if (path == null) return null;
        Path p = Path.of(path);
        if (p.isAbsolute() || cwd == null || cwd.isBlank()) return p.normalize().toString();
        return Path.of(cwd).resolve(p).normalize().toString();
    }

    public static String relativePath(Path path, String cwd) {
        try {
            Path base = Path.of(cwd).toAbsolutePath().normalize();
            Path abs = path.toAbsolutePath().normalize();
            if (abs.startsWith(base)) return base.relativize(abs).toString();
        } catch (Exception ignored) {}
        return path.toString();
    }

    public static int countNewlines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int c = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') c++;
        return c;
    }

    public static Path resolveDir(String cwd) {
        return cwd != null ? Path.of(cwd) : Path.of(System.getProperty("user.dir"));
    }

    public record TruncatedResult(String text, Map<String, Object> details) {}

    public static TruncatedResult truncateToFile(String text, int maxChars, String tempPrefix) throws IOException {
        if (text == null) text = "";
        if (text.length() <= maxChars) return new TruncatedResult(text, Map.of());
        Path file = Files.createTempFile(tempPrefix, ".txt");
        Files.writeString(file, text);
        var details = new LinkedHashMap<String, Object>();
        details.put("truncated", true);
        details.put("fullOutputPath", file.toString());
        details.put("maxChars", maxChars);
        String visible = truncateHeadTail(text, maxChars, file.toString());
        return new TruncatedResult(visible, Map.copyOf(details));
    }

    public static String truncateHeadTail(String text, int maxChars, String fullOutputPath) {
        int headChars = maxChars * 3 / 4;
        int tailChars = maxChars - headChars;
        int skipped = text.length() - maxChars;
        String head = text.substring(0, headChars);
        String tail = text.substring(text.length() - tailChars);
        return head
            + "\n\n[...truncated " + formatSize(skipped) + ". Full output: " + fullOutputPath + "...]\n\n"
            + tail;
    }

    public static String truncateHeadTail(String text, int maxChars) {
        if (text == null || text.isBlank()) return "";
        if (text.length() <= maxChars) return text;
        int headChars = maxChars * 3 / 4;
        int tailChars = maxChars - headChars;
        int skipped = text.length() - maxChars;
        return text.substring(0, headChars)
            + "\n\n[...truncated " + formatSize(skipped) + "...]\n\n"
            + text.substring(text.length() - tailChars);
    }

    private static String formatSize(int bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    public static String fmtCount(int value) {
        if (value >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        if (value >= 1_000) return String.format(Locale.ROOT, "%.1fK", value / 1_000.0);
        return Integer.toString(value);
    }

    public static String shortenCwd(String cwd) {
        String home = System.getProperty("user.home");
        if (home == null) return cwd;
        String resolved = Path.of(cwd).toAbsolutePath().toString();
        String resolvedHome = Path.of(home).toAbsolutePath().toString();
        if (!resolved.startsWith(resolvedHome)) return cwd;
        if (resolved.equals(resolvedHome)) return "~";
        return "~" + resolved.substring(resolvedHome.length());
    }

    public static String deltaPrefix(String full, String previous) {
        if (full == null || full.isEmpty()) return "";
        if (previous == null || previous.isEmpty()) return full;
        return full.startsWith(previous) ? full.substring(previous.length()) : full;
    }

    public static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public static String firstLine(String text) {
        if (text == null || text.isBlank()) return "";
        return text.stripTrailing().lines().findFirst().orElse("");
    }
}
