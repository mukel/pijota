package org.pijota.tui;

import org.pijota.config.AppConfig;
import org.pijota.config.OAuthLogin;
import org.pijota.config.ProviderCatalog;
import org.pijota.config.SettingsManager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LoginCommandHandlerTest {
    private static final ProviderCatalog.OAuth OAUTH = new ProviderCatalog.OAuth("device", "token", null, "client",
                                    "scope");
    private static final ProviderCatalog.OAuth OPENAI_OAUTH = new ProviderCatalog.OAuth("device", "token",
                                    "oauth-token", "client", "scope");

    @Test
    void showLoginSelectorReportsWhenNoOAuthProvidersExist() {
        var host = new FakeHost();
        var handler = handler(host, List.of(provider("local", "Local", null)), new FakeTokenStore(),
                                        (ignoredProvider, ignoredOAuth) -> null, ignoredProvider -> null);

        handler.showLoginSelector();

        assertEquals(List.of("no providers support OAuth login"), host.errors);
        assertTrue(host.shownProviders.isEmpty());
    }

    @Test
    void showLoginSelectorShowsOAuthProviders() {
        var host = new FakeHost();
        var handler = handler(host, List.of(provider("openai", "OpenAI", OAUTH)), new FakeTokenStore(),
                                        (ignoredProvider, ignoredOAuth) -> null, ignoredProvider -> null);

        handler.showLoginSelector();

        assertEquals(List.of(new LoginProviderItem("openai", "OpenAI", "OAuth", false)), host.shownProviders);
        assertTrue(host.promptCleared);
        assertEquals(1, host.redraws);
    }

    @Test
    void showLogoutSelectorIncludesLoginStatus() {
        var host = new FakeHost();
        var store = new FakeTokenStore();
        store.accessTokens.put("openai", "token");
        var handler = handler(host, List.of(provider("openai", "OpenAI", OAUTH),
                                        provider("anthropic", "Anthropic", OAUTH)), store,
                                        (ignoredProvider, ignoredOAuth) -> null, ignoredProvider -> null);

        handler.showLogoutSelector();

        assertEquals("logged in", host.shownProviders.get(0).detailText());
        assertEquals("not logged in", host.shownProviders.get(1).detailText());
    }

    @Test
    void confirmLoginRunsOAuthAndSavesToken() {
        var host = new FakeHost();
        host.selected = new LoginProviderItem("openai", "OpenAI", "OAuth", false);
        var store = new FakeTokenStore();
        var handler = handler(host, List.of(provider("openai", "OpenAI", OAUTH)), store,
                                        (ignoredProvider, ignoredOAuth) -> new OAuthLogin.TokenResult("access",
                                                                        "refresh", 123L, "account"),
                                        provider -> provider + "-default");

        handler.showLoginSelector();
        handler.confirmProviderSelection();

        assertTrue(host.deactivated);
        assertEquals(List.of("Starting OAuth login for OpenAI...",
                                        System.lineSeparator() + "Logged in to OpenAI  model: openai-default"),
                                        host.statuses);
        assertEquals("access", store.savedAccessToken);
        assertEquals("token", store.savedTokenUrl);
        assertEquals("client", store.savedClientId);
        assertEquals("openai-default", host.defaultModel);
    }

    @Test
    void confirmLoginUsesOpenAITokenEndpointWhenConfigured() {
        var host = new FakeHost();
        host.selected = new LoginProviderItem("openai", "OpenAI", "OAuth", false);
        var store = new FakeTokenStore();
        var handler = handler(host, List.of(provider("openai", "OpenAI", OPENAI_OAUTH)), store,
                                        (ignoredProvider, ignoredOAuth) -> new OAuthLogin.TokenResult("access",
                                                                        "refresh", 123L, "account"),
                                        ignoredProvider -> null);

        handler.showLoginSelector();
        handler.confirmProviderSelection();

        assertEquals("oauth-token", store.savedTokenUrl);
    }

    @Test
    void confirmLoginReportsMissingOAuthConfig() {
        var host = new FakeHost();
        host.selected = new LoginProviderItem("openai", "OpenAI", "OAuth", false);
        var providers = new FakeProviders(List.of(provider("openai", "OpenAI", OAUTH)));
        providers.oauth = Optional.empty();
        var handler = new LoginCommandHandler(host, providers, FakeTokenStore::new, (ignoredProvider,
                                        ignoredOAuth) -> new OAuthLogin.TokenResult("access", null, 0, null),
                                        ignoredProvider -> null);

        handler.showLoginSelector();
        handler.confirmProviderSelection();

        assertEquals(List.of("OpenAI has no OAuth config"), host.errors);
    }

    @Test
    void confirmLoginReportsOAuthFailureAndResumesTui() {
        var host = new FakeHost();
        host.selected = new LoginProviderItem("openai", "OpenAI", "OAuth", false);
        var handler = handler(host, List.of(provider("openai", "OpenAI", OAUTH)), new FakeTokenStore(),
                                        (ignoredProvider, ignoredOAuth) -> {
                                            throw new IOException("denied");
                                        }, ignoredProvider -> null);

        handler.showLoginSelector();
        handler.confirmProviderSelection();

        assertEquals(List.of("Login failed: denied"), host.errors);
    }

    @Test
    void confirmLogoutRemovesStoredCredentials() {
        var host = new FakeHost();
        host.selected = new LoginProviderItem("openai", "OpenAI", "logged in", false);
        var store = new FakeTokenStore();
        var handler = handler(host, List.of(provider("openai", "OpenAI", OAUTH)), store,
                                        (ignoredProvider, ignoredOAuth) -> null, ignoredProvider -> null);

        handler.showLogoutSelector();
        handler.confirmProviderSelection();

        assertEquals("openai", store.removedProvider);
        assertEquals("openai", host.removedApiKeyProvider);
        assertEquals(List.of("Logged out of OpenAI"), host.statuses);
    }

    private static LoginCommandHandler handler(FakeHost host, List<ProviderCatalog.Provider> providers,
                                    FakeTokenStore store, LoginCommandHandler.OAuthClient oauthClient,
                                    LoginCommandHandler.DefaultModelResolver defaultModels) {
        return new LoginCommandHandler(host, new FakeProviders(providers), () -> store, oauthClient, defaultModels);
    }

    private static ProviderCatalog.Provider provider(String id, String name, ProviderCatalog.OAuth oauth) {
        return new ProviderCatalog.Provider(id, name, "responses", "https://example.com", List.of(), null, Map.of(),
                                        oauth, null);
    }

    private static final class FakeProviders implements LoginCommandHandler.ProviderSource {
        private final List<ProviderCatalog.Provider> providers;
        Optional<ProviderCatalog.OAuth> oauth;

        FakeProviders(List<ProviderCatalog.Provider> providers) {
            this.providers = providers;
            this.oauth = providers.stream().map(ProviderCatalog.Provider::oauth).filter(o -> o != null).findFirst();
        }

        @Override
        public List<ProviderCatalog.Provider> providers() {
            return providers;
        }

        @Override
        public Optional<ProviderCatalog.OAuth> oauth(String provider) {
            return oauth;
        }
    }

    private static final class FakeTokenStore implements LoginCommandHandler.TokenStore {
        final Map<String, String> accessTokens = new HashMap<>();
        String savedAccessToken;
        String savedTokenUrl;
        String savedClientId;
        String removedProvider;

        @Override
        public String getAccessToken(String provider) {
            return accessTokens.get(provider);
        }

        @Override
        public void save(String provider, String accessToken, String refreshToken, long expiresAt, String accountId,
                                        String tokenUrl, String clientId) {
            savedAccessToken = accessToken;
            savedTokenUrl = tokenUrl;
            savedClientId = clientId;
        }

        @Override
        public void remove(String provider) {
            removedProvider = provider;
        }
    }

    private static final class FakeHost implements LoginCommandHandler.Host {
        /** A refresh after a credential change reads this; the fake has no keys, so nothing is probed. */
        @Override
        public SettingsManager settings() {
            return new SettingsManager(new AppConfig(), ignored -> {});
        }

        List<LoginProviderItem> shownProviders = List.of();
        LoginProviderItem selected;
        boolean deactivated;
        boolean promptCleared;
        int redraws;
        String defaultModel;
        String removedApiKeyProvider;
        final List<String> errors = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();

        @Override
        public void showProviders(List<LoginProviderItem> items) {
            shownProviders = items;
        }

        @Override
        public Optional<LoginProviderItem> selectedProvider() {
            return Optional.ofNullable(selected);
        }

        @Override
        public void deactivateProviderSelector() {
            deactivated = true;
        }

        @Override
        public void clearPrompt() {
            promptCleared = true;
        }

        @Override
        public void redrawPrompt() {
            redraws++;
        }

        @Override
        public void appendError(String message) {
            errors.add(message);
        }

        @Override
        public void appendStatus(String message) {
            statuses.add(message);
        }

        @Override
        public void submitAuthTask(Runnable task) {
            task.run();
        }

        @Override
        public void setDefaultModelForProvider(String provider, String model) {
            defaultModel = model;
        }

        @Override
        public void removeApiKey(String provider) {
            removedApiKeyProvider = provider;
        }
    }
}
