package org.pijota.extensions.tmux;

/** One tmux session pijota drives, for work that needs a terminal that outlives a single command. */
public record TmuxSession(String id, String tmuxName, String friendlyName, String command, String workdir,
                                String paneId, long createdAt, boolean autoCleanup, boolean alive) {

    public TmuxSession(String id, String tmuxName, String friendlyName, String command, String workdir, String paneId,
                                    long createdAt, boolean autoCleanup) {
        this(id, tmuxName, friendlyName, command, workdir, paneId, createdAt, autoCleanup, true);
    }

    public TmuxSession withAlive(boolean alive) {
        return new TmuxSession(id, tmuxName, friendlyName, command, workdir, paneId, createdAt, autoCleanup, alive);
    }
}
