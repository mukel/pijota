package ai.pijota.messages;

/**
 * Token usage for a single API request: input (prompt) and output (completion) tokens, the cache read/creation input
 * tokens a provider reports separately (Anthropic prompt caching), and the reasoning tokens a provider bills as part of
 * the output (OpenAI/GPT-5 & o-series thinking). {@code inputTokens} excludes the cached ones, so the real prompt size
 * is {@code inputTokens + cacheReadTokens + cacheWriteTokens}; the context meter adds them back.
 * {@code reasoningTokens} is a subset of {@code outputTokens}, surfaced in {@code /usage} so thinking-heavy turns are
 * visible.
 */
public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                                long reasoningTokens) {
    public static final Usage EMPTY = new Usage(0, 0, 0, 0, 0);

    /** Usage with no separately-reported cache or reasoning tokens (the common case). */
    public Usage(long inputTokens, long outputTokens) {
        this(inputTokens, outputTokens, 0, 0, 0);
    }
}
