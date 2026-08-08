package org.pijota.llm;

import org.pijota.config.ProviderCatalog;
import org.pijota.testkit.FakeHttpClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    void fetchReadsFireworksContextLengthAndImageCapability() throws Exception {
        // fireworks self-reports context_length + supports_image_input per model; a vision model with a 1M window must
        // not collapse to the 128k text-only fallback.
        var json = "{\"data\":[" + "{\"id\":\"accounts/fireworks/models/kimi-k3\",\"context_length\":1048576,\"supports_image_input\":true,\"supports_tools\":true},"
                                        + "{\"id\":\"accounts/fireworks/models/gpt-oss-120b\",\"context_length\":131072,\"supports_image_input\":false},"
                                        + "{\"id\":\"accounts/fireworks/models/no-meta\"}]}";

        var models = ModelRefresher.fetchOpenAiCompatible(FakeHttpClient.ok(json), provider("fireworks"), "tok", null,
                                        false);

        assertEquals(3, models.size(), models.toString());
        var k3 = models.get(0);
        assertEquals(1048576L, k3.contextWindow(), "context_length must be honored, not the 128k default");
        assertEquals("Kimi K3", k3.displayName(), "the accounts/fireworks/models/ prefix must not leak into the name");
        assertTrue(k3.inputModalities().contains(org.pijota.llm.Modality.IMAGE),
                                        "supports_image_input=true must surface the image modality");
        var textOnly = models.get(1);
        assertEquals(131072L, textOnly.contextWindow());
        assertNull(textOnly.input(), "supports_image_input=false leaves modalities unspecified (text-only)");
        var noMeta = models.get(2);
        assertEquals(128000L, noMeta.contextWindow(), "a model with no context field still falls back to the default");
        assertNull(noMeta.input());
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
    void displayNameStripsTheFireworksAccountPathPrefix() {
        assertEquals("Kimi K3", ModelRefresher.displayName("accounts/fireworks/models/kimi-k3"));
        assertEquals("Kimi K3 Fast", ModelRefresher.displayName("accounts/fireworks/routers/kimi-k3-fast"));
    }

    @Test
    void displayNameLeavesPlainAndNonAccountsPathsUntouched() {
        // Only the accounts/<org>/(models|routers)/ boilerplate is stripped; other ids pass through verbatim.
        assertEquals("Gpt 4", ModelRefresher.displayName("gpt-4"));
        assertEquals("Openai/gpt Oss 120b", ModelRefresher.displayName("openai/gpt-oss-120b"));
        assertEquals("Accounts/fireworks/datasets/foo", // not models/ or routers/ -> no strip, but still title-cased
                                        ModelRefresher.displayName("accounts/fireworks/datasets/foo"));
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
        assertEquals(42, ModelRefresher.firstLong(node, 0, "a", "b"));
    }

    @Test
    void firstLongFallsBackToSecond() throws Exception {
        var mapper = new ObjectMapper();
        var node = mapper.readTree("{\"b\": 99}");
        assertEquals(99, ModelRefresher.firstLong(node, 0, "a", "b"));
    }

    @Test
    void firstLongReturnsFallbackWhenBothMissing() throws Exception {
        var mapper = new ObjectMapper();
        var node = mapper.readTree("{}");
        assertEquals(100L, ModelRefresher.firstLong(node, 100, "a", "b"));
    }

    @Test
    void firstLongReturnsFallbackWhenNotConvertible() throws Exception {
        var mapper = new ObjectMapper();
        var node = mapper.readTree("{\"a\": \"not-a-number\"}");
        assertEquals(100L, ModelRefresher.firstLong(node, 100, "a", "b"));
    }

    /**
     * Also a hermeticity check: a refresh that asked nobody and found nothing must not write, or a unit test leaves a
     * models-cache.json in the home of whoever ran the suite. The redirected home is belt and braces.
     */
    @Test
    void refreshWithNullSettingsReturnsEmptyResultAndWritesNothing(@TempDir Path home) {
        String saved = System.setProperty("pijota.home", home.toString());
        try {
            var result = ModelRefresher.refresh(null);
            assertNotNull(result);
            assertEquals(0, result.providersChecked());
            assertEquals(0, result.modelsFound());
            assertTrue(result.errors().isEmpty());
            assertFalse(Files.exists(home.resolve("models-cache.json")), "nothing was discovered, so nothing to save");
        } finally {
            if (saved == null)
                System.clearProperty("pijota.home");
            else
                System.setProperty("pijota.home", saved);
        }
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

    /**
     * One wording for both frontends. The TUI and the CLI each had their own, and neither said the result is kept, so
     * the command read as something to run again every session, which is what it used to be before the cache.
     */
    @Test
    void theSummarySaysWhatHappenedAndThatItSticks() {
        assertEquals("No provider to ask: none has a credential yet, and none is configured without one.",
                                        new ModelRefresher.Result(0, 0, List.of()).summary());
        assertEquals("Asked 2 providers; none offered a model list.",
                                        new ModelRefresher.Result(2, 0, List.of()).summary());
        assertEquals("Found 7 models from 1 provider, remembered for next time.",
                                        new ModelRefresher.Result(1, 7, List.of()).summary());
    }
}
