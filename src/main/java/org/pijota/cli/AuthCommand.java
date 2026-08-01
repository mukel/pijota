package org.pijota.cli;

import org.pijota.config.OAuthTokenStore;
import org.pijota.config.ProviderCatalog;
import org.pijota.config.SettingsManager;

import java.util.List;

/**
 * {@code pijota auth print-api-key|print-bearer-token --provider <name>}: hands a provider credential to another tool,
 * so a script does not have to re-implement pijota's resolution order (settings, then {@code ${ENV}} interpolation,
 * then the provider's env vars) or read the OAuth store and its refresh logic.
 *
 * <p>
 * The two are deliberately separate. {@code print-api-key} returns the static key only; {@code print-bearer-token}
 * returns the OAuth access token, refreshed if it has expired. Collapsing them would hand callers whichever credential
 * happened to be configured, and an {@code Authorization: Bearer} header built from a raw API key fails in ways that
 * look like a server problem.
 *
 * <p>
 * Only the credential is written to stdout, with a trailing newline and nothing else, so {@code $(pijota auth ...)}
 * composes. Every diagnostic goes to stderr.
 */
public final class AuthCommand {
    private AuthCommand() {}

    /** The subcommand name, matched positionally by {@link ArgParser} before any other parsing. */
    public static final String NAME = "auth";

    private static final String USAGE = """
                                    USAGE: pijota auth <command> --provider <name>

                                    COMMANDS:
                                      print-api-key       Print the provider's API key (settings, ${ENV} interpolation, then env vars)
                                      print-bearer-token  Print the provider's OAuth access token, refreshing it if expired

                                    EXAMPLES:
                                      curl -H "Authorization: Bearer $(pijota auth print-bearer-token --provider anthropic)" ...
                                      export OPENAI_API_KEY="$(pijota auth print-api-key --provider openai)"
                                    """;

    /**
     * @param args
     *            everything after {@code auth}
     * @return process exit code
     */
    public static int run(List<String> args, SettingsManager settings) {
        String command = args.isEmpty() ? "" : args.get(0);
        if (command.isBlank() || command.equals("--help") || command.equals("-h")) {
            System.err.print(USAGE);
            return command.isBlank() ? 2 : 0;
        }
        String provider = valueOf(args, "--provider");
        if (provider == null || provider.isBlank()) {
            System.err.println("auth: --provider is required (e.g. --provider anthropic)");
            return 2;
        }
        return switch (command) {
            case "print-api-key" -> printApiKey(provider, settings);
            case "print-bearer-token" -> printBearerToken(provider);
            default -> {
                System.err.println("auth: unknown command \"" + command
                                                + "\" (use print-api-key or print-bearer-token)");
                yield 2;
            }
        };
    }

    private static int printApiKey(String provider, SettingsManager settings) {
        if (!SettingsManager.requiresApiKey(provider)) {
            System.err.println("auth: provider \"" + provider + "\" does not use an API key");
            return 1;
        }
        // resolveApiKey returns an OAuth token when one is stored, which is not what this command promises.
        String key = settings.resolveApiKeyOnly(provider);
        if (key == null || key.isBlank()) {
            System.err.println("auth: no API key for \"" + provider + "\"; set " + settings.apiKeyHint(provider)
                                            + " or add it to settings.json");
            return 1;
        }
        System.out.println(key);
        return 0;
    }

    private static int printBearerToken(String provider) {
        if (ProviderCatalog.oauth(provider).isEmpty()) {
            System.err.println("auth: provider \"" + provider + "\" does not use OAuth; try print-api-key");
            return 1;
        }
        String token = new OAuthTokenStore().getAccessToken(provider); // refreshes when expired
        if (token == null || token.isBlank()) {
            System.err.println("auth: not logged in to \"" + provider + "\"; run /login " + provider);
            return 1;
        }
        System.out.println(token);
        return 0;
    }

    private static String valueOf(List<String> args, String flag) {
        for (int i = 0; i < args.size() - 1; i++)
            if (args.get(i).equals(flag))
                return args.get(i + 1);
        return null;
    }
}
