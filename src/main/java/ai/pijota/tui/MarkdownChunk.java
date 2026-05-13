package ai.pijota.tui;

record MarkdownChunk(String markdown, boolean blankLine) {
    static MarkdownChunk markdown(String markdown) {
        return new MarkdownChunk(markdown, false);
    }

    static MarkdownChunk blank() {
        return new MarkdownChunk("", true);
    }
}
