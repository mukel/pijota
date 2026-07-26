package org.pijota.llm;

public enum ThinkingLevel {
    OFF("thinking off", 0), MINIMAL("minimal", 1024), LOW("low", 2048), MEDIUM("medium", 8192), HIGH("high",
                                    16384), XHIGH("xhigh", 32768), MAX("max", 65536);

    private final String label;
    private final int budgetTokens;

    ThinkingLevel(String label, int budgetTokens) {
        this.label = label;
        this.budgetTokens = budgetTokens;
    }

    public String label() {
        return label;
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
