package org.pijota.llm;

import org.pijota.config.ProviderCatalog;
import org.pijota.testkit.FakeHttpClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelRefresherTest {

    private static ProviderCatalog.Provider provider(String id) {
        return new ProviderCatalog.Provider(id, id, "openai", "https://api.test/v1", null, null, null, null, null);
    }

    @Test
    void fetchParsesTheOpenAiDataArrayAndSkipsBlankIds() throws Exception {
        var json = "{\"data\":[{\"id\":\"gpt-x\"},{\"id\":\"gpt-y\"},{\"id\":\"\"}]}";

        var models = ModelRefresher.fetchOpenAiCompatible(FakeHttpClient.ok(json), provider("test"), "tok", null,
                                        false);

        assertEquals(2, models.size(), models.toString());
        assertEquals("gpt-x", models.get(0).id());
        assertEquals("test", models.get(0).provider());
    }

    @Test
    void fetchParsesTheCodexModelsArrayAndSkipsHidden() throws Exception {
        var json = "{\"models\":[{\"slug\":\"codex-1\",\"display_name\":\"Codex One\",\"context_window\":200000},"
                                        + "{\"slug\":\"hidden\",\"visibility\":\"none\"}]}";

        var models = ModelRefresher.fetchOpenAiCompatible(FakeHttpClient.ok(json), provider("openai"), "tok", null,
                                        false);

        assertEquals(1, models.size(), models.toString());
        assertEquals("codex-1", models.get(0).id());
        assertEquals("Codex One", models.get(0).displayName());
        assertEquals(200000L, models.get(0).contextWindow());
    }

    @Test
    void fetchThrowsOnAnHttpErrorStatus() {
        var client = FakeHttpClient.status(500, "boom");
        assertThrows(Exception.class, () -> ModelRefresher.fetchOpenAiCompatible(client, provider("test"), "tok", null,
                                        false));
    }

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
        var mapper = new ObjectMapper();
        var node = mapper.readTree("{\"a\": 42, \"b\": 99}");
        assertEquals(42, ModelRefresher.firstLong(node, "a", "b", 0));
    }

    @Test
    void firstLongFallsBackToSecond() throws Exception {
        var mapper = new ObjectMapper();
        var node = mapper.readTree("{\"b\": 99}");
        assertEquals(99, ModelRefresher.firstLong(node, "a", "b", 0));
    }

    @Test
    void firstLongReturnsFallbackWhenBothMissing() throws Exception {
        var mapper = new ObjectMapper();
        var node = mapper.readTree("{}");
        assertEquals(100L, ModelRefresher.firstLong(node, "a", "b", 100));
    }

    @Test
    void firstLongReturnsFallbackWhenNotConvertible() throws Exception {
        var mapper = new ObjectMapper();
        var node = mapper.readTree("{\"a\": \"not-a-number\"}");
        assertEquals(100L, ModelRefresher.firstLong(node, "a", "b", 100));
    }

    @Test
    void refreshWithNullSettingsReturnsEmptyResult() {
        var result = ModelRefresher.refresh(null);
        assertNotNull(result);
        assertEquals(0, result.providersChecked());
        assertEquals(0, result.modelsFound());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void aRefreshFailureIsAlwaysOneReadableLine() {
        // An exception with no message used to print "provider: null", and a provider's JSON error body used to
        // spill across the transcript. A refresh summary must stay scannable when several providers fail at once.
        String noMessage = ModelRefresher.oneLineReason(new IllegalStateException());
        assertFalse(noMessage.isBlank(), "never blank");
        assertFalse(noMessage.contains("null"), "never the literal null: " + noMessage);

        String jsonBody = ModelRefresher.oneLineReason(new RuntimeException(
                                        "HTTP 401 - {\n  \"error\": {\n    \"message\": \"Incorrect API key\"\n  }\n}"));
        assertFalse(jsonBody.contains("\n"), "collapsed to one line: " + jsonBody);
        assertTrue(jsonBody.length() <= 161, "bounded: " + jsonBody.length());
        // ErrorUtil digs the human sentence out of a provider's JSON error body; the braces never reach the user.
        assertTrue(jsonBody.contains("Incorrect API key"), "the useful part survives: " + jsonBody);
        assertFalse(jsonBody.contains("{"), "no raw JSON: " + jsonBody);
    }
}
