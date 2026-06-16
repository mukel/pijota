package ai.pijota.tui;

import ai.pijota.agent.SessionDescriptor;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A {@link SelectorItem} wrapper over the generic {@link SessionDescriptor} — the descriptor derives the readable label
 * (shared by every frontend); this class only adds the terminal selector's presentation.
 */
public record SessionItem(Path path, String name, String description, long mtime,
                                boolean named) implements SelectorItem {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d HH:mm")
                                    .withZone(ZoneId.systemDefault());

    public static List<SessionItem> fromPaths(List<Path> paths) {
        return SessionDescriptor.describeAll(paths).stream().map(
                                        d -> new SessionItem(d.path(), d.id(), d.description(), d.mtime(), d.named()))
                                        .toList();
    }

    static SessionDescriptor.ParsedDescription descriptionFromLine(String line) {
        return SessionDescriptor.parseLine(line);
    }

    static String clean(String s) {
        return SessionDescriptor.clean(s);
    }

    static String truncate(String s) {
        return SessionDescriptor.truncate(s);
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
}
