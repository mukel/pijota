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

    /** The probe only ever asks a provider the user configured, so a test that wants it asked has to register one. */
    @org.junit.jupiter.api.BeforeEach
    void registerALocalProvider() {
        ProviderCatalog.registerUserProviders(List.of(new ProviderCatalog.Provider("local", "Local", "openai",
                                        "http://127.0.0.1:11434/v1", List.of(), "m", java.util.Map.of(), null, null)));
    }

    @org.junit.jupiter.api.AfterEach
    void forgetIt() {
        ProviderCatalog.registerUserProviders(List.of());
    }

    private static ProviderCatalog.Provider provider(String id) {
        return new ProviderCatalog.Provider(id, id, "openai", "https://api.test/v1", null, null, null, null, null);
    }

    /**
     * n_ctx belongs to the server process, not the model, so it is asked for directly rather than remembered. Only of a
     * local server: every remote provider would 404 it, and asking would put a speculative round-trip in front of each.
     */
    @Test
    void theServedWindowIsAskedForDirectlyAndOnlyOfALocalServer() throws Exception {
        var local = FakeHttpClient.ok("{\"n_ctx\":8192,\"n_ctx_train\":131072}");
        assertEquals(8192L, ModelRefresher.servedContextWindow(() -> local, "local", "http://127.0.0.1:11434/v1"));

        var remote = FakeHttpClient.ok("{\"n_ctx\":8192}");
        assertEquals(0L, ModelRefresher.servedContextWindow(() -> remote, "local", "https://api.test/v1"),
                                        "a remote host must not be asked, whatever it would have said");
        assertEquals(0L, ModelRefresher.servedContextWindow(() -> local, "local", null), "no base URL, nothing to ask");
    }

    /**
     * Nothing is built for a server that is never asked. The probe used to take a ready-made
     * {@link java.net.http.HttpClient}, so every startup constructed one -- selector thread and executor included --
     * and handed it to a call that declined before the first byte, for every user whose provider is a vendor's public
     * API.
     */
    @Test
    void theClientIsBuiltOnlyIfTheServerIsActuallyAsked() throws Exception {
        var built = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<java.net.http.HttpClient> client = () -> {
            built.incrementAndGet();
            return FakeHttpClient.ok("{\"n_ctx\":8192}");
        };

        assertEquals(0L, ModelRefresher.servedContextWindow(client, "local", "https://api.test/v1"));
        assertEquals(0, built.get(), "a remote host is settled by its address; no client needed");

        assertEquals(8192L, ModelRefresher.servedContextWindow(client, "local", "http://127.0.0.1:11434/v1"));
        assertEquals(1, built.get(), "and exactly one is built when there is something to ask");
    }

    /**
     * A bundled provider is never asked, whatever its base URL says. Deciding it by address means resolving the name
     * first, and that lookup was landing on the startup path of every user whose provider is a vendor's public API.
     */
    @Test
    void aBundledProviderIsNeverProbedAndCostsNoLookup() throws Exception {
        var client = FakeHttpClient.ok("{\"n_ctx\":8192}");
        assertEquals(0L, ModelRefresher.servedContextWindow(() -> client, "openai", "http://127.0.0.1:11434/v1"),
                                        "loopback or not, a bundled entry is not the user's machine");
        assertEquals(0L, ModelRefresher.servedContextWindow(() -> client, null, "http://127.0.0.1:11434/v1"));
    }

    /** A server that says nothing leaves the catalogue's answer alone rather than zeroing it. */
    @Test
    void aServerThatDoesNotAnswerLeavesTheCatalogueValue() throws Exception {
        assertEquals(0L, ModelRefresher.servedContextWindow(() -> FakeHttpClient.ok("not json"), "local",
                                        "http://127.0.0.1:11434/v1"));
        assertEquals(0L, ModelRefresher.servedContextWindow(() -> FakeHttpClient.status(404, "nope"), "local",
                                        "http://127.0.0.1:11434/v1"));
    }

    /** Nothing per-server reaches the model cache any more: it would come back after a restart looking measured. */
    @Test
    void aLocalServersWindowIsNeverStampedOntoTheCatalogueRows() throws Exception {
        var client = FakeHttpClient.sequence("{\"data\":[{\"id\":\"gemma.gguf\"}]}", "{\"n_ctx\":8192}");

        var models = ModelRefresher.fetchOpenAiCompatible(client, provider("local"), "tok", null, false);

        assertEquals(128_000L, models.get(0).contextWindow(),
                                        "the row keeps the catalogue default; the served window is resolved live");
        assertTrue(client.lastRequest().uri().toString().contains("/models"), "and /props is not consulted here");
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
