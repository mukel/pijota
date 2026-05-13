package ai.pijota.tui;

enum BuiltInSlashCommand {
    HELP("/help", "Show commands and key bindings"),
    SETTINGS("/settings", "Open settings menu"),
    MODEL("/model", "Select or search models"),
    THEME("/theme", "List or switch themes"),
    COMPACT("/compact", "Manually compact the conversation"),
    SAVE("/save", "Save session to a file"),
    LOAD("/load", "Load and resume a session"),
    DELETE("/delete", "Delete a saved session"),
    SESSIONS("/sessions", "List saved sessions"),
    NAME("/name", "Set session display name"),
    CONTEXT("/context", "Show token usage breakdown"),
    UNDO("/undo", "Edit previous message"),
    COPY("/copy", "Copy last assistant response"),
    SESSION("/session", "Show session info"),
    NEW("/new", "Start a new session"),
    HOTKEYS("/hotkeys", "Show keyboard shortcuts"),
    LOGIN("/login", "Log in to a provider via OAuth"),
    LOGOUT("/logout", "Remove stored credentials for a provider"),
    TREE("/tree", "Show conversation tree"),
    FORK("/fork", "Fork a new session from a previous message"),
    CLONE("/clone", "Clone current branch to a new session"),
    TRANSCEND("/transcend", "Rebuild and restart pijota"),
    QUIT("/quit", "Exit pijota");

    final String name;
    final String description;

    BuiltInSlashCommand(String name, String description) {
        this.name = name;
        this.description = description;
    }
}
