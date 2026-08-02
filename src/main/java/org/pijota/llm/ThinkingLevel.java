package org.pijota.llm;

public enum ThinkingLevel {
    // Labels are bare level names; callers add their own context ("thinking " + label, " · " + label). OFF was
    // "thinking off", which rendered as "thinking thinking off" when cycling and " · thinking off" in the footer.
    OFF("off", 0, "thinkingOff"), MINIMAL("minimal", 1024, "thinkingMinimal"), LOW("low", 2048, "thinkingLow"), MEDIUM(
                                    "medium", 8192,
                                    "thinkingMedium"), HIGH("high", 16384, "thinkingHigh"), XHIGH("xhigh", 32768,
                                                                    "thinkingXhigh"), MAX("max", 65536, "thinkingMax");

    private final String label;
    private final int budgetTokens;
    private final String themeSlot;

    ThinkingLevel(String label, int budgetTokens, String themeSlot) {
        this.label = label;
        this.budgetTokens = budgetTokens;
        this.themeSlot = themeSlot;
    }

    public String label() {
        return label;
    }

    /**
     * The theme slot that colours this level. Themes ramp it from near-invisible at {@code off} to a loud hue at
     * {@code max}, so the status bar says how much reasoning is being bought without spending a word on it.
     */
    public String themeSlot() {
        return themeSlot;
    }

    public int budgetTokens() {
        return budgetTokens;
    }

    public ThinkingLevel next() {
        return switch (this) {
            case OFF -> MINIMAL;
            case MINIMAL -> LOW;
            case LOW -> MEDIUM;
            case MEDIUM -> HIGH;
            case HIGH -> XHIGH;
            case XHIGH -> MAX;
            case MAX -> OFF;
        };
    }

    /** The spellings a user may write, in ramp order, for anything that has to say what it expected. */
    public static java.util.List<String> names() {
        return java.util.Arrays.stream(values()).map(ThinkingLevel::label).toList();
    }

    /**
     * Whether {@code s} names a level. {@link #fromString} answers MEDIUM for a blank <em>and</em> for a typo, which is
     * the right default but the wrong silence: a caller that has a user's own words in hand should be able to say it
     * did not recognise them.
     */
    public static boolean isKnown(String s) {
        return s != null && names().contains(s.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** One wording for "that is not a level", so the flag and the settings file report a typo the same way. */
    public static String unknownValueMessage(String source, String value) {
        return "pijota: unknown " + source + " '" + value + "' (expected: " + String.join(", ", names()) + "), using "
                                        + MEDIUM.label();
    }

    public static ThinkingLevel fromString(String s) {
        if (s == null || s.isBlank())
            return MEDIUM;
        String lower = s.trim().toLowerCase();
        return switch (lower) {
            case "off", "thinking off" -> OFF;
            case "minimal" -> MINIMAL;
            case "low" -> LOW;
            case "medium" -> MEDIUM;
            case "high" -> HIGH;
            case "xhigh" -> XHIGH;
            case "max" -> MAX;
            default -> {
                try {
                    yield ThinkingLevel.valueOf(s.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    yield MEDIUM;
                }
            }
        };
    }
}
