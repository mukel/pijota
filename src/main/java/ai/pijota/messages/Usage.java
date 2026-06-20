package ai.pijota.messages;

/** Token usage for a single API request: input (prompt) tokens and output (completion) tokens. */
public record Usage(long inputTokens, long outputTokens) {
    public static final Usage EMPTY = new Usage(0, 0);
}
