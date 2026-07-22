package ai.pijota.messages;

/**
 * Token usage for a single API request: input (prompt) and output (completion) tokens, plus any cache read/creation
 * input tokens the provider reports separately (Anthropic prompt caching). {@code inputTokens} excludes the cached
 * ones, so the real prompt size is {@code inputTokens + cachedInputTokens}; the context meter adds them back.
 */
public record Usage(long inputTokens, long outputTokens, long cachedInputTokens) {
    public static final Usage EMPTY = new Usage(0, 0, 0);

    /**
     * Usage with no separately-reported cache tokens (the common case; the provider folds caching into inputTokens).
     */
    public Usage(long inputTokens, long outputTokens) {
        this(inputTokens, outputTokens, 0);
    }
}
