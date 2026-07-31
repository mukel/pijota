package org.pijota.tui;

import org.pijota.config.OAuthLogin;
import org.pijota.config.OAuthTokenStore;
import org.pijota.config.ProviderCatalog;
import org.pijota.llm.ModelRegistry;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

final class LoginCommandHandler {
    interface Host extends TuiHost {
        void showProviders(List<LoginProviderItem> items);

        Optional<LoginProviderItem> selectedProvider();

        void deactivateProviderSelector();

        void submitAuthTask(Runnable task);

        void setDefaultModelForProvider(String provider, String model) throws IOException;

        void removeApiKey(String provider) throws IOException;
    }

    interface ProviderSource {
        List<ProviderCatalog.Provider> providers();

        Optional<ProviderCatalog.OAuth> oauth(String provider);
    }

    interface TokenStore {
        String getAccessToken(String provider);

        void save(String provider, String accessToken, String refreshToken, long expiresAt, String accountId,
                                        String tokenUrl, String clientId);

        void remove(String provider);
    }

    interface TokenStoreFactory {
        TokenStore create();
    }

    interface OAuthClient {
        OAuthLogin.TokenResult login(ProviderCatalog.OAuth oauth, Consumer<String> out) throws Exception;
    }

    interface DefaultModelResolver {
        String defaultModelForProvider(String provider);
    }

    private enum Mode {
        LOGIN, LOGOUT
    }

    private final Host host;
    private final ProviderSource providers;
    private final TokenStoreFactory tokenStores;
    private final OAuthClient oauthClient;
    private final DefaultModelResolver defaultModels;
    private Mode mode = Mode.LOGIN;

    LoginCommandHandler(Host host) {
        this(host, new CatalogProviderSource(), OAuthTokenStoreAdapter::new, LoginCommandHandler::login,
                                        ModelRegistry::defaultModelForProvider);
    }

    LoginCommandHandler(Host host, ProviderSource providers, TokenStoreFactory tokenStores, OAuthClient oauthClient,
                                    DefaultModelResolver defaultModels) {
        this.host = host;
        this.providers = providers;
        this.tokenStores = tokenStores;
        this.oauthClient = oauthClient;
        this.defaultModels = defaultModels;
    }

    void showLoginSelector() {
        var items = providers.providers().stream().filter(p -> p.oauth() != null)
                                        .map(p -> new LoginProviderItem(p.id(), p.displayName(), "OAuth", false))
                                        .toList();
        if (items.isEmpty()) {
            host.appendError("no providers support OAuth login");
            return;
        }
        mode = Mode.LOGIN;
        show(items);
    }

    void showLogoutSelector() {
        var store = tokenStores.create();
        var items = providers.providers().stream().filter(p -> p.oauth() != null).map(p -> new LoginProviderItem(p.id(),
                                        p.displayName(),
                                        store.getAccessToken(p.id()) != null ? "logged in" : "not logged in", false))
                                        .toList();
        if (items.isEmpty()) {
            host.appendError("no providers to log out");
            return;
        }
        mode = Mode.LOGOUT;
        show(items);
    }

    void confirmProviderSelection() {
        if (mode == Mode.LOGOUT)
            confirmLogoutSelection();
        else
            confirmLoginSelection();
    }

    private void confirmLoginSelection() {
        var chosen = host.selectedProvider().orElse(null);
        if (chosen == null)
            return;
        closeSelector();
        host.appendStatus("Starting OAuth login for " + chosen.titleText() + "...");
        host.submitAuthTask(() -> loginProvider(chosen));
    }

    private void loginProvider(LoginProviderItem chosen) {
        try {
            var oauth = providers.oauth(chosen.id());
            if (oauth.isEmpty()) {
                host.appendError(chosen.titleText() + " has no OAuth config");
                return;
            }
            var oauthCfg = oauth.get();

            // Output goes through the TUI append stream (not raw System.out) so the device URL renders cleanly above
            // the prompt and the input loop stays live, letting Esc cancel the in-flight login (see handleEsc).
            var result = oauthClient.login(oauthCfg, line -> host.appendPlain(List.of(line)));

            String tokenUrl = oauthCfg.oauthTokenEndpoint() != null && !oauthCfg.oauthTokenEndpoint().isBlank()
                                            ? oauthCfg.oauthTokenEndpoint()
                                            : oauthCfg.tokenEndpoint();
            tokenStores.create().save(chosen.id(), result.accessToken(), result.refreshToken(), result.expiresAt(),
                                            result.accountId(), tokenUrl, oauthCfg.clientId());
            String defaultModel = defaultModels.defaultModelForProvider(chosen.id());
            if (defaultModel != null) {
                host.setDefaultModelForProvider(chosen.id(), defaultModel);
            }
            host.appendStatus(System.lineSeparator() + "Logged in to " + chosen.titleText() + "  model: "
                                            + (defaultModel != null ? defaultModel : ""));
            host.redrawPrompt();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                host.appendStatus("Login cancelled");
            } else {
                host.appendError("Login failed: " + (e.getMessage() != null
                                                ? e.getMessage()
                                                : e.getClass().getSimpleName()));
            }
            host.redrawPrompt();
        }
    }

    private void confirmLogoutSelection() {
        var chosen = host.selectedProvider().orElse(null);
        if (chosen == null)
            return;
        closeSelector();
        tokenStores.create().remove(chosen.id());
        try {
            host.removeApiKey(chosen.id());
        } catch (IOException ignored) {}
        host.appendStatus("Logged out of " + chosen.titleText());
    }

    private void show(List<LoginProviderItem> items) {
        host.showProviders(items);
        host.clearPrompt();
        host.redrawPrompt();
    }

    private void closeSelector() {
        host.deactivateProviderSelector();
        host.clearPrompt();
        host.redrawPrompt();
    }

    private static OAuthLogin.TokenResult login(ProviderCatalog.OAuth oauth, Consumer<String> out) throws Exception {
        if (oauth.oauthTokenEndpoint() != null && !oauth.oauthTokenEndpoint().isBlank()) {
            return OAuthLogin.loginOpenAI(oauth.deviceEndpoint(), oauth.tokenEndpoint(), oauth.oauthTokenEndpoint(),
                                            oauth.clientId(), out);
        }
        return OAuthLogin.login(oauth.deviceEndpoint(), oauth.tokenEndpoint(), oauth.clientId(), oauth.scope(), out);
    }

    private static final class CatalogProviderSource implements ProviderSource {
        @Override
        public List<ProviderCatalog.Provider> providers() {
            return ProviderCatalog.providers();
        }

        @Override
        public Optional<ProviderCatalog.OAuth> oauth(String provider) {
            return ProviderCatalog.oauth(provider);
        }
    }

    private static final class OAuthTokenStoreAdapter implements TokenStore {
        private final OAuthTokenStore store = new OAuthTokenStore();

        @Override
        public String getAccessToken(String provider) {
            return store.getAccessToken(provider);
        }

        @Override
        public void save(String provider, String accessToken, String refreshToken, long expiresAt, String accountId,
                                        String tokenUrl, String clientId) {
            store.save(provider, accessToken, refreshToken, expiresAt, accountId, tokenUrl, clientId);
        }

        @Override
        public void remove(String provider) {
            store.remove(provider);
        }
    }
}
