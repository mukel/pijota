package org.pijota.extensions.tmux;

public record TmuxSession(String id, String tmuxName, String friendlyName, String command, String workdir,
                                String paneId, long createdAt, boolean autoCleanup, boolean alive) {

    public TmuxSession(String id, String tmuxName, String friendlyName, String command, String workdir, String paneId,
                                    long createdAt, boolean autoCleanup) {
        this(id, tmuxName, friendlyName, command, workdir, paneId, createdAt, autoCleanup, true);
    }

    public TmuxSession withAlive(boolean alive) {
        return new TmuxSession(id, tmuxName, friendlyName, command, workdir, paneId, createdAt, autoCleanup, alive);
    }

    public TmuxSession withPaneId(String paneId) {
        return new TmuxSession(id, tmuxName, friendlyName, command, workdir, paneId, createdAt, autoCleanup, alive);
    }
}
