package org.pijota.testkit;

public final class AnsiStripper {
    private static final String ANSI_PATTERN = "\\u001B\\[[0-?]*[ -/]*[@-~]";

    private AnsiStripper() {}

    public static String strip(String text) {
        return text == null ? null : text.replaceAll(ANSI_PATTERN, "");
    }
}
