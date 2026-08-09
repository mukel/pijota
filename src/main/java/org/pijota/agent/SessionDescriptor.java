package org.pijota.agent;

import org.pijota.util.Util;

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

    /** Placeholder description for a session with no readable user content (only a header, or assistant-only). */
    static final String EMPTY = "(empty)";
    /** Placeholder description for a session file that couldn't be read/parsed at all. */
    static final String UNREADABLE = "(unreadable)";

    public static List<SessionDescriptor> describeAll(List<Path> paths) {
        var out = new ArrayList<SessionDescriptor>(paths.size());
        for (Path p : paths)
            out.add(describe(p));
        return out;
    }

    /**
     * Describes {@code paths}, keeping only the sessions worth resuming, those with a real label
     * ({@link #hasContent()}). Empty, assistant-only, or unreadable sessions carry no
     * {@code "(empty)"}/{@code "(unreadable)"} label a user could recognize, and resuming one is no different from
     * starting fresh, so they'd only clutter a recent-sessions list or a resume picker.
     */
    public static List<SessionDescriptor> describeResumable(List<Path> paths) {
        return describeAll(paths).stream().filter(SessionDescriptor::hasContent).toList();
    }

    /**
     * Like {@link #describeResumable(List)} but stops after {@code limit} matches. The stream is lazy, so it parses
     * only as many (most-recent-first) session files as it needs. Lets the launch banner show its few recent sessions
     * without reading every file on disk at startup.
     */
    public static List<SessionDescriptor> describeResumable(List<Path> paths, int limit) {
        return paths.stream().map(SessionDescriptor::describe).filter(SessionDescriptor::hasContent).limit(limit)
                                        .toList();
    }

    /**
     * Whether this session has resumable content: a name or a real first user message, as opposed to the
     * {@code "(empty)"}/{@code "(unreadable)"} placeholders shown for abandoned, assistant-only, or corrupt files.
     */
    public boolean hasContent() {
        return named || (!EMPTY.equals(description) && !UNREADABLE.equals(description));
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

    /**
     * One-line label for a flat selector: description, message/token counts, and a relative age when known.
     *
     * <p>
     * Facts of one kind, joined the way the rest of the UI joins them. This is what every non-TUI frontend shows in its
     * resume picker, so it has to read like the TUI's.
     */
    public String label() {
        var sb = new StringBuilder(description);
        // Skip counts for empty/unreadable sessions (0 msgs). They'd just read "(empty) . 0 msgs".
        if (messageCount > 0) {
            sb.append(CHIP).append(Util.plural(messageCount, "msg"));
            if (estTokens > 0)
                sb.append(CHIP).append("~").append(formatTokens(estTokens));
        }
        if (mtime > 0)
            sb.append(CHIP).append(relativeAge());
        return sb.toString();
    }

    /** Joins facts of the same kind; two spaces would promise a column alignment these rows do not have. */
    private static final String CHIP = " \u00b7 ";

    /** Rough token estimate from a character count (~4 chars per token), the project-wide heuristic. */
    public static long estimateTokens(long chars) {
        return Util.approxTokens(chars);
    }

    /** Compact token count using the project's standard count formatter, e.g. {@code "812 tok"} / {@code "12K tok"}. */
    public static String formatTokens(long tokens) {
        return Util.fmtCount(tokens) + " tok";
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
        try (var lines = SessionManager.readLines(p)) {
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
                    continue; // header / compaction / model_change / cache_metadata · not conversation
                String role = (String) entry.getOrDefault("role", "");
                // Count conversational turns only, tool_result entries are plumbing and would inflate the picker
                // count.
                if ("user".equals(role) || "assistant".equals(role))
                    messages++;
                // Tokens, however, include every message's content (tool results consume context too).
                if (entry.get("content") instanceof String c)
                    chars += c.length();
                if (entry.get("thinking") instanceof String t)
                    chars += t.length();
                if (firstUser == null && "user".equals(role)) {
                    String c = clean((String) entry.get("content"));
                    if (c != null)
                        firstUser = truncate(c);
                }
            }
        } catch (IOException e) {
            return new Stats(UNREADABLE, false, 0, 0);
        }
        long estTokens = estimateTokens(chars);
        if (sessionName != null)
            return new Stats(sessionName, true, messages, estTokens);
        return new Stats(firstUser != null ? firstUser : EMPTY, false, messages, estTokens);
    }

    public static String clean(String s) {
        if (s == null || s.isBlank())
            return null;
        return Util.stripSystemReminders(s).replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
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

    private record Stats(String text, boolean named, int messageCount, long estTokens) {
    }
}
