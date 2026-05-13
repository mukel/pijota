package ai.pijota.tui;

record StyledLine(String text) {
    public StyledLine {
        if (text == null) text = "";
    }

    public static StyledLine of(String text) {
        return new StyledLine(text);
    }
}
