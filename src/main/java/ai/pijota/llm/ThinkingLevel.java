package ai.pijota.llm;

public enum ThinkingLevel {
    OFF("thinking off", 0),
    MINIMAL("minimal", 1024),
    LOW("low", 2048),
    MEDIUM("medium", 8192),
    HIGH("high", 16384);

    private final String label;
    private final int budgetTokens;

    ThinkingLevel(String label, int budgetTokens) {
        this.label = label;
        this.budgetTokens = budgetTokens;
    }

    public String label() { return label; }
    public int budgetTokens() { return budgetTokens; }

    public ThinkingLevel next() {
        return switch (this) {
            case OFF -> MINIMAL;
            case MINIMAL -> LOW;
            case LOW -> MEDIUM;
            case MEDIUM -> HIGH;
            case HIGH -> OFF;
        };
    }
}
