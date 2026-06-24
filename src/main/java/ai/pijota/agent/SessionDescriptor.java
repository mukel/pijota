package ai.pijota.agent;

import ai.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A frontend-agnostic, human-friendly description of a saved session: its id, a one-line description (the session's
 * given name if it has one, otherwise its first user message), whether it was explicitly named, and its last-modified
 * time. This is the single place that derives a readable label from a session file, so every frontend's session picker
 * (terminal, web, Telegram) shows the same thing rather than a bare uuid.
 */
public record SessionDescriptor(Path path, String id, String description, boolean named, long mtime, int messageCount,
                                long estTokens) {

    private static final int MAX_DESC_LEN = 80;

    public static List<SessionDescriptor> describeAll(List<Path> paths) {
        var out = new ArrayList<SessionDescriptor>(paths.size());
        for (Path p : paths)
            out.add(describe(p));
        return out;
    }

    public static SessionDescriptor describe(Path p) {
        String id = p.getFileName().toString();
        if (id.endsWith(".jsonl"))
            id = id.substring(0, id.length() - ".jsonl".length());
        var stats = readStats(p);
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            mtime = 0;
        }
        return new SessionDescriptor(p, id, stats.text(), stats.named(), mtime, stats.messageCount(),
                                        stats.estTokens());
    }

    /** One-line label for a flat selector: description, message/token counts, and a relative age when known. */
    public String label() {
        var sb = new StringBuilder(description);
        sb.append("  ·  ").append(messageCount).append(" msg").append(messageCount == 1 ? "" : "s");
        if (estTokens > 0)
            sb.append("  ·  ~").append(formatTokens(estTokens));
        if (mtime > 0)
            sb.append("  ·  ").append(relativeAge());
        return sb.toString();
    }

    /** Compact token count, e.g. {@code "812 tok"} or {@code "12.3k tok"}. */
    public static String formatTokens(long tokens) {
        return tokens < 1000 ? tokens + " tok" : String.format("%.1fk tok", tokens / 1000.0);
    }

    public String relativeAge() {
        return relativeAge(mtime);
    }

    /** Human-friendly age of a last-modified timestamp ("just now", "5m ago", … / "unknown"). */
    public static String relativeAge(long mtime) {
        if (mtime <= 0)
            return "unknown";
        long ago = System.currentTimeMillis() - mtime;
        if (ago < 60_000)
            return "just now";
        if (ago < 3_600_000)
            return (ago / 60_000) + "m ago";
        if (ago < 86_400_000)
            return (ago / 3_600_000) + "h ago";
        return (ago / 86_400_000) + "d ago";
    }

    @SuppressWarnings("unchecked")
    private static Stats readStats(Path p) {
        String firstUser = null;
        String sessionName = null;
        int messages = 0;
        long chars = 0;
        try (var lines = Files.lines(p)) {
            for (var it = lines.iterator(); it.hasNext();) {
                Map<String, Object> entry;
                try {
                    entry = Util.MAPPER.readValue(it.next(), Map.class);
                } catch (Exception bad) {
                    continue; // skip blank/corrupt lines, same resilience as parseLine
                }
                String type = (String) entry.getOrDefault("type", "message");
                if ("session_info".equals(type)) {
                    String name = clean((String) entry.get("name"));
                    if (name != null)
                        sessionName = truncate(name);
                    continue;
                }
                if (!"message".equals(type))
                    continue; // header / compaction / model_change / cache_metadata — not conversation
                messages++;
                if (entry.get("content") instanceof String c)
                    chars += c.length();
                if (entry.get("thinking") instanceof String t)
                    chars += t.length();
                if (firstUser == null && "user".equals(entry.getOrDefault("role", ""))) {
                    String c = clean((String) entry.get("content"));
                    if (c != null)
                        firstUser = truncate(c);
                }
            }
        } catch (IOException e) {
            return new Stats("(unreadable)", false, 0, 0);
        }
        long estTokens = (long) Math.ceil(chars / 4.0);
        if (sessionName != null)
            return new Stats(sessionName, true, messages, estTokens);
        return new Stats(firstUser != null ? firstUser : "(empty)", false, messages, estTokens);
    }

    @SuppressWarnings("unchecked")
    public static ParsedDescription parseLine(String line) {
        try {
            Map<String, Object> entry = Util.MAPPER.readValue(line, Map.class);
            if ("session_info".equals(entry.get("type"))) {
                String name = clean((String) entry.get("name"));
                return new ParsedDescription(null, name == null ? null : truncate(name));
            }
            if (!"message".equals(entry.getOrDefault("type", "message")))
                return null;
            if (!"user".equals(entry.getOrDefault("role", "")))
                return null;
            return new ParsedDescription(truncate(clean((String) entry.get("content"))), null);
        } catch (Exception e) {
            return null;
        }
    }

    public static String clean(String s) {
        if (s == null || s.isBlank())
            return null;
        return s.replaceAll("(?s)<system-reminder>.*?</system-reminder>", "").replace('\n', ' ').replace('\r', ' ')
                                        .replaceAll("\\s+", " ").trim();
    }

    public static String truncate(String s) {
        if (s == null || s.length() <= MAX_DESC_LEN)
            return s;
        int cut = MAX_DESC_LEN;
        while (cut > MAX_DESC_LEN - 10 && cut > 0 && !Character.isWhitespace(s.charAt(cut)))
            cut--;
        if (cut <= MAX_DESC_LEN - 10 || cut <= 0)
            cut = MAX_DESC_LEN;
        return s.substring(0, cut) + "…";
    }

    public record ParsedDescription(String firstUser, String sessionName) {
    }

    private record Stats(String text, boolean named, int messageCount, long estTokens) {
    }
}
