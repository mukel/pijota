package ai.pijota.tui;

import ai.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record SessionItem(Path path, String name, String description, long mtime,
                                boolean named) implements SelectorItem {

    private static final int MAX_DESC_LEN = 80;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d HH:mm")
                                    .withZone(ZoneId.systemDefault());

    public static List<SessionItem> fromPaths(List<Path> paths) {
        var items = new ArrayList<SessionItem>();
        for (var p : paths) {
            String name = p.getFileName().toString();
            if (name.endsWith(".jsonl"))
                name = name.substring(0, name.length() - 6);
            var description = readDescription(p);
            long mtime;
            try {
                mtime = Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                mtime = 0;
            }
            items.add(new SessionItem(p, name, description.text(), mtime, description.named()));
        }
        return items;
    }

    private static Description readDescription(Path p) {
        String firstUser = null;
        String sessionName = null;
        try (var lines = Files.lines(p)) {
            for (var it = lines.iterator(); it.hasNext();) {
                var parsed = descriptionFromLine(it.next());
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
    private static ParsedDescription descriptionFromLine(String line) {
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

    private static String clean(String s) {
        if (s == null || s.isBlank())
            return null;
        return s.replaceAll("(?s)<system-reminder>.*?</system-reminder>", "").replace('\n', ' ').replace('\r', ' ')
                                        .replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= MAX_DESC_LEN)
            return s;
        int cut = MAX_DESC_LEN;
        while (cut > MAX_DESC_LEN - 10 && cut > 0 && !Character.isWhitespace(s.charAt(cut)))
            cut--;
        if (cut <= MAX_DESC_LEN - 10 || cut <= 0)
            cut = MAX_DESC_LEN;
        return s.substring(0, cut) + "…";
    }

    @Override
    public String searchText() {
        return name + " " + description + " " + path.getFileName();
    }

    @Override
    public String titleText() {
        return description;
    }

    @Override
    public String detailText() {
        long ago = System.currentTimeMillis() - mtime;
        String age;
        if (ago < 60_000)
            age = "just now";
        else if (ago < 3600_000)
            age = (ago / 60_000) + "m ago";
        else if (ago < 86400_000)
            age = (ago / 3600_000) + "h ago";
        else
            age = (ago / 86400_000) + "d ago";
        String shortName = name.length() > 14 ? name.substring(0, 14) : name;
        String date = mtime > 0 ? DATE.format(Instant.ofEpochMilli(mtime)) : "unknown";
        return age + "  " + date + "  " + shortName;
    }

    private record ParsedDescription(String firstUser, String sessionName) {
    }
    private record Description(String text, boolean named) {
    }
}
