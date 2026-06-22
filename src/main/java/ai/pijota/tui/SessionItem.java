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
        String shortName = name.length() > 14 ? name.substring(0, 14) : name;
        String date = mtime > 0 ? DATE.format(Instant.ofEpochMilli(mtime)) : "unknown";
        return age + "  " + date + "  " + shortName;
    }
}
