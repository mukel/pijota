package ai.pijota.messages;

public record Usage(long inputTokens, long outputTokens) {
    public static final Usage EMPTY = new Usage(0, 0);
}
