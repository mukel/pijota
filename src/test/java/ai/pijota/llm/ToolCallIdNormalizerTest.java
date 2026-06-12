package ai.pijota.llm;

import java.util.LinkedHashSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallIdNormalizerTest {

    private static String openaiProv;
    private static String anthropicProv;
    private static String unknownProv;

    @BeforeAll
    static void lookupProviders() {
        openaiProv = resolveOpenAiProvider();
        anthropicProv = "anthropic";
        unknownProv = "nonexistent-provider-xyz";
    }

    private static String resolveOpenAiProvider() {
        var provs = ai.pijota.config.ProviderCatalog.providers();
        for (var p : provs) {
            if ("openai".equals(p.apiType()))
                return p.id();
        }
        return "deepseek";
    }

    @Test
    void normalizeOpenAiTruncatesTo40Chars() {
        var longId = "a".repeat(100);
        var result = ToolCallIdNormalizer.normalize(longId, openaiProv);
        assertTrue(result.length() <= 40);
    }

    @Test
    void normalizeAnthropicTruncatesTo64Chars() {
        var longId = "a".repeat(100);
        var result = ToolCallIdNormalizer.normalize(longId, anthropicProv);
        assertTrue(result.length() <= 64);
    }

    @Test
    void normalizeOpenAiStripsAfterPipe() {
        var result = ToolCallIdNormalizer.normalize("call_abc|something", openaiProv);
        assertEquals("call_abc", result);
    }

    @Test
    void normalizeReplacesControlChars() {
        var result = ToolCallIdNormalizer.normalize("hello\nworld", anthropicProv);
        assertFalse(result.contains("\n"));
        assertTrue(result.contains("_"));
    }

    @Test
    void normalizeBlankIdProducesFallback() {
        var result = ToolCallIdNormalizer.normalize("   ", openaiProv);
        assertFalse(result.isBlank());
        assertTrue(result.startsWith("call_"));
    }

    @Test
    void normalizeNullIdProducesFallback() {
        var result = ToolCallIdNormalizer.normalize(null, openaiProv);
        assertTrue(result.startsWith("call_"));
    }

    @Test
    void normalizeTrimsWhitespace() {
        var result = ToolCallIdNormalizer.normalize("  hello  ", anthropicProv);
        assertEquals("hello", result);
    }

    @Test
    void normalizePreservesAlphanumeric() {
        var result = ToolCallIdNormalizer.normalize("test_123-id", anthropicProv);
        assertEquals("test_123-id", result);
    }

    @Test
    void uniqueReturnsNormalizedWhenNoCollision() {
        var used = new LinkedHashSet<String>();
        var result = ToolCallIdNormalizer.unique("call_abc", anthropicProv, used);
        assertEquals("call_abc", result);
        assertTrue(used.contains("call_abc"));
    }

    @Test
    void uniqueWithCollisionAppendsSuffix() {
        var used = new LinkedHashSet<String>();
        used.add("call_abc");

        var result = ToolCallIdNormalizer.unique("call_abc", anthropicProv, used);
        assertNotEquals("call_abc", result);
        assertTrue(result.startsWith("call"));
        assertTrue(result.contains("_"));
    }

    @Test
    void uniqueOpenAirTruncates() {
        var used = new LinkedHashSet<String>();
        var longId = "a".repeat(80);
        var result = ToolCallIdNormalizer.unique(longId, openaiProv, used);
        assertTrue(result.length() <= 40);
    }

    @Test
    void normalizeOpenAiWithPipeAndLong() {
        var id = "call_very_long_name_that_exceeds_limit|extra_stuff";
        var result = ToolCallIdNormalizer.normalize(id, openaiProv);
        assertTrue(result.length() <= 40);
        assertFalse(result.contains("|"));
    }

    @Test
    void normalizePreservesHyphensAndUnderscores() {
        var result = ToolCallIdNormalizer.normalize("my-call_id", anthropicProv);
        assertEquals("my-call_id", result);
    }

    @Test
    void normalizeUnknownProviderDefaultsTo64() {
        var longId = "a".repeat(100);
        var result = ToolCallIdNormalizer.normalize(longId, unknownProv);
        assertTrue(result.length() <= 64);
    }
}
