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
public record SessionDescriptor(Path path, String id, String description, boolean named, long mtime) {

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
        var desc = readDescription(p);
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            mtime = 0;
        }
        return new SessionDescriptor(p, id, desc.text(), desc.named(), mtime);
    }

    /** One-line label for a flat selector: the description plus a relative age when known. */
    public String label() {
        return mtime > 0 ? description + "  ·  " + relativeAge() : description;
    }

    public String relativeAge() {
        long ago = System.currentTimeMillis() - mtime;
        if (mtime <= 0)
            return "unknown";
        if (ago < 60_000)
            return "just now";
        if (ago < 3_600_000)
            return (ago / 60_000) + "m ago";
        if (ago < 86_400_000)
            return (ago / 3_600_000) + "h ago";
        return (ago / 86_400_000) + "d ago";
    }

    private static Description readDescription(Path p) {
        String firstUser = null;
        String sessionName = null;
        try (var lines = Files.lines(p)) {
            for (var it = lines.iterator(); it.hasNext();) {
                var parsed = parseLine(it.next());
                if (parsed == null)
                    continue;
                if (parsed.sessionName() != null)
                    sessionName = parsed.sessionName();
                if (firstUser == null && parsed.firstUser() != null)
                    firstUser = parsed.firstUser();
            }
            if (sessionName != null)
                return new Description(sessionName, true);
            return new Description(firstUser != null ? firstUser : "(empty)", false);
        } catch (IOException e) {
            return new Description("(unreadable)", false);
        }
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

    private record Description(String text, boolean named) {
    }
}
