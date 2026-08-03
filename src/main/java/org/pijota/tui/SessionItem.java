package org.pijota.tui;

import org.pijota.agent.SessionDescriptor;
import org.pijota.util.Util;

import java.nio.file.Path;
import java.util.List;

/**
 * A {@link SelectorItem} wrapper over the generic {@link SessionDescriptor}: the descriptor derives the readable label
 * (shared by every frontend); this class only adds the terminal selector's presentation.
 */
public record SessionItem(Path path, String name, String description, long mtime, boolean named, int messageCount,
                                long estTokens) implements SelectorItem {

    public static List<SessionItem> fromPaths(List<Path> paths) {
        return of(SessionDescriptor.describeResumable(paths));
    }

    /**
     * Like {@link #fromPaths(List)} but describes at most {@code limit} sessions, for the launch banner, which shows
     * only a few and shouldn't parse every session file on disk at startup.
     */
    public static List<SessionItem> fromPaths(List<Path> paths, int limit) {
        return of(SessionDescriptor.describeResumable(paths, limit));
    }

    private static List<SessionItem> of(List<SessionDescriptor> descriptors) {
        return descriptors.stream().map(d -> new SessionItem(d.path(), d.id(), d.description(), d.mtime(), d.named(),
                                        d.messageCount(), d.estTokens())).toList();
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
        // Marked, so a cut id cannot be mistaken for a whole one: a bare substring rendered "4e103344-6a49-", which
        // looks exactly like a complete short id and is not one.
        String shortName = Util.ellipsize(name, 14);
        // omit counts for empty/unreadable sessions (0 msgs)
        String counts = messageCount > 0
                                        ? TuiCopy.CHIP + Util.plural(messageCount, "msg") + (estTokens > 0
                                                                        ? TuiCopy.CHIP + "~" + SessionDescriptor
                                                                                                        .formatTokens(estTokens)
                                                                        : "")
                                        : "";
        // These are facts of one kind, not columns: ages and counts vary in width, so every row broke the alignment
        // that two spaces promised. The launch banner's own session rows already read this way.
        //
        // No absolute date. It said the same thing as the relative age sitting next to it, and the width it cost was
        // taken from the far end of the row -- where the session id lives, the one field here that is not repeated
        // anywhere else and the one you need to pass to -s.
        return age + counts + TuiCopy.CHIP + shortName;
    }
}
