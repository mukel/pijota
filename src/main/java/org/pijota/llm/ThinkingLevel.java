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
