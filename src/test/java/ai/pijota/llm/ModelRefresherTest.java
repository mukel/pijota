package ai.pijota.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelRefresherTest {

    @Test
    void displayNameConvertsKebabCase() {
        assertEquals("Gpt 4", ModelRefresher.displayName("gpt-4"));
    }

    @Test
    void displayNameConvertsUnderscore() {
        assertEquals("Claude Sonnet", ModelRefresher.displayName("claude_sonnet"));
    }

    @Test
    void displayNameCapitalizesWords() {
        assertEquals("Deepseek Chat", ModelRefresher.displayName("deepseek-chat"));
    }

    @Test
    void displayNameSingleWord() {
        assertEquals("Gemini", ModelRefresher.displayName("gemini"));
    }

    @Test
    void displayNameMixedKebabAndUnderscore() {
        assertEquals("My Cool Model", ModelRefresher.displayName("my-cool_model"));
    }

    @Test
    void displayNameEmptyReturnsEmpty() {
        assertEquals("", ModelRefresher.displayName(""));
    }

    @Test
    void displayNameWithMultipleDashes() {
        assertEquals("A B", ModelRefresher.displayName("a--b"));
    }

    @Test
    void firstLongReturnsFirstWhenPresent() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = mapper.readTree("{\"a\": 42, \"b\": 99}");
        assertEquals(42, ModelRefresher.firstLong(node, "a", "b", 0));
    }

    @Test
    void firstLongFallsBackToSecond() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = mapper.readTree("{\"b\": 99}");
        assertEquals(99, ModelRefresher.firstLong(node, "a", "b", 0));
    }

    @Test
    void firstLongReturnsFallbackWhenBothMissing() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = mapper.readTree("{}");
        assertEquals(100L, ModelRefresher.firstLong(node, "a", "b", 100));
    }

    @Test
    void firstLongReturnsFallbackWhenNotConvertible() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = mapper.readTree("{\"a\": \"not-a-number\"}");
        assertEquals(100L, ModelRefresher.firstLong(node, "a", "b", 100));
    }
}
