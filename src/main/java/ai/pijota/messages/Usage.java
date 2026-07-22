package ai.pijota.messages;

/**
 * Token usage for a single API request: input (prompt) and output (completion) tokens, plus the cache read/creation
 * input tokens a provider reports separately (Anthropic prompt caching). {@code inputTokens} excludes the cached ones,
 * so the real prompt size is {@code inputTokens + cacheReadTokens + cacheWriteTokens}; the context meter adds them
 * back, and {@code /usage} shows the read (cache hit) vs write (cache miss) split.
 */
public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
    public static final Usage EMPTY = new Usage(0, 0, 0, 0);

    /**
     * Usage with no separately-reported cache tokens (the common case; the provider folds caching into inputTokens).
     */
    public Usage(long inputTokens, long outputTokens) {
        this(inputTokens, outputTokens, 0, 0);
    }
}
