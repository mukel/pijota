package org.pijota.tui;

import org.pijota.agent.SessionDescriptor;
import org.pijota.util.Util;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A {@link SelectorItem} wrapper over the generic {@link SessionDescriptor} — the descriptor derives the readable label
 * (shared by every frontend); this class only adds the terminal selector's presentation.
 */
public record SessionItem(Path path, String name, String description, long mtime, boolean named, int messageCount,
                                long estTokens, boolean showPath) implements SelectorItem {

    public SessionItem(Path path, String name, String description, long mtime, boolean named, int messageCount,
                                    long estTokens) {
        this(path, name, description, mtime, named, messageCount, estTokens, false);
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d HH:mm")
                                    .withZone(ZoneId.systemDefault());

    public static List<SessionItem> fromPaths(List<Path> paths) {
        return of(SessionDescriptor.describeResumable(paths));
    }

    public static List<SessionItem> fromPaths(List<Path> paths, boolean showPath) {
        return of(SessionDescriptor.describeResumable(paths)).stream().map(i -> i.withShowPath(showPath)).toList();
    }

    /**
     * Like {@link #fromPaths(List)} but describes at most {@code limit} sessions — for the launch banner, which shows
     * only a few and shouldn't parse every session file on disk at startup.
     */
    public static List<SessionItem> fromPaths(List<Path> paths, int limit) {
        return of(SessionDescriptor.describeResumable(paths, limit));
    }

    private static List<SessionItem> of(List<SessionDescriptor> descriptors) {
        return descriptors.stream().map(d -> new SessionItem(d.path(), d.id(), d.description(), d.mtime(), d.named(),
                                        d.messageCount(), d.estTokens())).toList();
    }

    SessionItem withShowPath(boolean show) {
        return new SessionItem(path, name, description, mtime, named, messageCount, estTokens, show);
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
        String age = SessionDescriptor.relativeAge(mtime); // single source for age formatting
        if (showPath)
            return path.toString();
        String shortName = name.length() > 14 ? name.substring(0, 14) : name;
        String date = mtime > 0 ? DATE.format(Instant.ofEpochMilli(mtime)) : "unknown";
        // omit counts for empty/unreadable sessions (0 msgs)
        String counts = messageCount > 0
                                        ? "  " + Util.plural(messageCount, "msg") + (estTokens > 0
                                                                        ? "  ~" + SessionDescriptor.formatTokens(
                                                                                                        estTokens)
                                                                        : "")
                                        : "";
        return age + "  " + date + counts + "  " + shortName;
    }
}
