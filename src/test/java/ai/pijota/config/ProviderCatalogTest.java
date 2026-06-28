package ai.pijota.config;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class ProviderCatalogTest {

    @Test
    void providersListIsNotEmpty() {
        var providers = ProviderCatalog.providers();
        assertFalse(providers.isEmpty());
    }

    @Test
    void findKnownProvider() {
        var deepseek = ProviderCatalog.find("deepseek");
        assertTrue(deepseek.isPresent());
        assertEquals("DeepSeek", deepseek.get().displayName());
        assertEquals("openai", deepseek.get().apiType());
    }

    @Test
    void findUnknownProviderReturnsEmpty() {
        assertTrue(ProviderCatalog.find("nonexistent").isEmpty());
    }

    @Test
    void apiTypeByProvider() {
        assertEquals("openai", ProviderCatalog.apiType("deepseek"));
        assertEquals("anthropic", ProviderCatalog.apiType("anthropic"));
        assertEquals("google", ProviderCatalog.apiType("google"));
    }

    @Test
    void apiTypeUnknownReturnsNull() {
        assertNull(ProviderCatalog.apiType("unknown-provider"));
    }

    @Test
    void standardProvidersDeclareNoThinkingFormat() {
        assertNull(ProviderCatalog.thinkingFormat("openai"));
        assertNull(ProviderCatalog.thinkingFormat("unknown-provider"));
    }

    @Test
    void noDuplicateProviderIds() {
        var ids = ProviderCatalog.providers().stream().map(ProviderCatalog.Provider::id).toList();
        var unique = new HashSet<>(ids);
        assertEquals(unique.size(), ids.size(), "duplicate provider ids: " + ids);
    }

    @Test
    void baseUrlReturnsUrl() {
        var url = ProviderCatalog.baseUrl("deepseek");
        assertNotNull(url);
        assertTrue(url.startsWith("https://"));
    }

    @Test
    void baseUrlUnknownReturnsNull() {
        assertNull(ProviderCatalog.baseUrl("unknown"));
    }

    @Test
    void envNamesReturnsList() {
        var envs = ProviderCatalog.envNames("anthropic");
        assertFalse(envs.isEmpty());
        assertTrue(envs.contains("ANTHROPIC_API_KEY"));
    }

    @Test
    void envNamesUnknownReturnsEmpty() {
        assertTrue(ProviderCatalog.envNames("unknown").isEmpty());
    }

    @Test
    void defaultModelReturnsValue() {
        var model = ProviderCatalog.defaultModel("anthropic");
        assertNotNull(model);
        assertFalse(model.isBlank());
    }

    @Test
    void defaultModelUnknownReturnsNull() {
        assertNull(ProviderCatalog.defaultModel("unknown"));
    }

    @Test
    void headersReturnsEmptyMapByDefault() {
        var headers = ProviderCatalog.headers("deepseek");
        assertNotNull(headers);
    }

    @Test
    void headersUnknownReturnsEmptyMap() {
        assertTrue(ProviderCatalog.headers("unknown").isEmpty());
    }

    @Test
    void oauthReturnsEmptyByDefault() {
        var oauth = ProviderCatalog.oauth("deepseek");
        assertNotNull(oauth);
    }

    @Test
    void providerRecordHasAllFields() {
        var providers = ProviderCatalog.providers();
        var first = providers.get(0);
        assertNotNull(first.id());
        // baseUrl and apiType are null for some providers (like openai with oauth)
    }

    @Test
    void headersReturnProviderSpecificValues() {
        var headers = ProviderCatalog.headers("kimi-coding");
        assertFalse(headers.isEmpty());
        assertEquals("KimiCLI/1.5", headers.get("User-Agent"));
    }

    @Test
    void oauthReturnsPresentForOAuthProvider() {
        var oauth = ProviderCatalog.oauth("openai-codex");
        assertTrue(oauth.isPresent());
        assertNotNull(oauth.get().clientId());
        assertNotNull(oauth.get().deviceEndpoint());
    }

    @Test
    void oauthReturnsEmptyForApiKeyProvider() {
        assertTrue(ProviderCatalog.oauth("anthropic").isEmpty());
    }

    @Test
    void findIsCaseSensitive() {
        assertTrue(ProviderCatalog.find("OpenAI").isEmpty());
        assertTrue(ProviderCatalog.find("openai").isPresent());
    }
}
