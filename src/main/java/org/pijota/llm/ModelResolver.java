package org.pijota.llm;

import org.pijota.cli.Args;
import org.pijota.config.ProviderCatalog;
import org.pijota.config.SettingsManager;
import org.pijota.util.CliErrorReporter;
import org.pijota.util.PijotaHome;

/**
 * Resolves the effective {@code (provider, model, apiKey)} a run should use, from CLI args + settings, the one gnarly
 * decision that used to sit inline in {@link org.pijota.AppBootstrap#bootstrap}. Kept separate so the rules
 * (print-alias, env detection, provider-switch-also-switches-model, the reserved-{@code alias} failure, missing-key
 * error, and the "did you mean" typo pre-flight) are readable and unit-testable on their own.
 */
public final class ModelResolver {

    /** The effective provider/model/apiKey for a run. {@code apiKey} may be null/blank when {@code --agent} is set. */
    public record Resolved(String provider, String model, String apiKey) {
    }

    private ModelResolver() {}

    public static Resolved resolve(Args args, SettingsManager settings) {
        String apiKey = args.apiKey();

        // Per-role model: print/-p uses the "print" alias when set and no explicit --model was given, so
        // non-interactive
        // runs can default to a cheaper/faster model (e.g. alias print -> alias/fast). ArgParser splits "alias/<name>"
        // into provider="alias" + model="<name>", so resolve the print role the same way rather than passing a bare
        // "alias/print" string (which resolveProviderModel would take literally).
        boolean usePrintAlias = args.model() == null && args.print() && settings.config().aliases() != null
                                        && settings.config().aliases().containsKey("print");
        var resolved = usePrintAlias
                                        ? ModelRegistry.resolveProviderModel(ModelRegistry.ALIAS_PROVIDER, "print",
                                                                        settings)
                                        : ModelRegistry.resolveProviderModel(args.provider(), args.model(), settings);
        // "alias" is reserved: if it survived resolution, the named alias is undefined. Fail clearly instead of
        // silently falling through to the default provider.
        if (ModelRegistry.ALIAS_PROVIDER.equalsIgnoreCase(resolved.provider()))
            throw new IllegalStateException("Unknown model alias '" + args.model()
                                            + "'. Define it under \"aliases\" in settings.json (name -> provider/model).");
        String provider = resolved.provider();
        String model = resolved.model();

        if (apiKey == null || apiKey.isBlank()) {
            if (provider != null && !provider.isBlank())
                apiKey = settings.resolveApiKey(provider);
            if (apiKey == null || (apiKey.isBlank() && SettingsManager.requiresApiKey(provider))) {
                if (args.provider() == null) {
                    String detected = settings.detectProviderFromEnv();
                    if (detected != null) {
                        provider = detected;
                        apiKey = settings.resolveApiKey(detected);
                        // Switching provider must switch the model too, otherwise we'd run the detected provider with
                        // the previous provider's model (e.g. openai-codex with "gpt-4o").
                        if (!ModelRegistry.isKnownProviderModel(detected, model))
                            model = ModelRegistry.defaultModelForProvider(detected);
                    }
                }
            }
        }
        if (apiKey == null || (apiKey.isBlank() && SettingsManager.requiresApiKey(provider))) {
            if (!args.agent()) {
                // Onboard only when the user asked for nothing in particular AND nothing is configured: that is a
                // first run. Someone who typed --provider/--model made a choice and wants to know what is wrong with
                // THAT choice, not a tour of how to get started.
                boolean chosen = args.provider() != null || args.model() != null;
                if (!chosen && ModelRegistry.availableModels(settings).isEmpty())
                    throw new SetupRequiredException(firstRunMessage());
                throw new IllegalStateException(missingApiKeyMessage(provider, model, settings));
            }
        }

        // Pre-flight typo check: an explicitly chosen model that isn't in a catalogued provider's list is usually a
        // typo. Suggest the closest known model up front instead of letting the provider API reject it after a wasted
        // round-trip. Non-fatal: new/uncatalogued models still pass straight through.
        if (args.model() != null && !ModelRegistry.isKnownProviderModel(provider, model)
                                        && !ModelRegistry.forProvider(provider).isEmpty()) {
            String chosenProvider = provider;
            String chosenModel = model;
            ModelRegistry.closestModel(chosenProvider, chosenModel).ifPresent(suggestion -> CliErrorReporter.warn("'"
                                            + chosenModel + "' is not a known " + chosenProvider
                                            + " model. Did you mean '" + suggestion + "'? Proceeding anyway."));
        }
        return new Resolved(provider, model, apiKey);
    }

    /**
     * Names the key missing for one provider. Always a diagnosis: the caller decides whether the situation is instead a
     * first run (nothing configured AND nothing asked for), which {@link #firstRunMessage} answers.
     */
    static String missingApiKeyMessage(String provider, String model, SettingsManager settings) {
        String hint = settings.apiKeyHint(provider);
        StringBuilder sb = new StringBuilder("No API key for ").append(provider).append('/').append(model).append('.');
        if (hint != null && !hint.isBlank())
            sb.append(" Set ").append(hint).append('.');
        if (ProviderCatalog.oauth(provider).isPresent())
            sb.append(" Or run `pijota login ").append(provider).append("`.");
        sb.append(" Use `pijota --list-models ").append(provider).append("` to see available models.");
        sb.append(" For a local/OpenAI-compatible server, add a keyless provider in ")
                                        .append(PijotaHome.resolve("settings.json")).append(".");
        return sb.toString();
    }

    /**
     * The very first thing a new user sees if they launch before configuring anything. It names the two ways in (sign
     * in, or export a key) with copy-pasteable commands, rather than reporting a failure about a model they never
     * chose.
     */
    static String firstRunMessage() {
        // An API key leads, because it is the path that works for everyone. The browser sign-in is one provider and
        // needs a ChatGPT subscription that most first-time users will not have; offering it first asks them to
        // discover that for themselves. Every variable is listed, from the catalog rather than a hardcoded few: this
        // is the one screen where "and 16 more" is the opposite of helpful, since the reader is looking for theirs.
        var envNames = ProviderCatalog.providers().stream().flatMap(p -> p.env().stream()).distinct().sorted().toList();

        var sb = new StringBuilder("No provider configured yet. Set an API key for any provider:\n\n");
        sb.append("  export ").append(envNames.isEmpty() ? "ANTHROPIC_API_KEY" : envNames.get(0)).append("=…\n\n");
        if (envNames.size() > 1) {
            sb.append("  any of: ");
            int column = "  any of: ".length();
            for (int i = 0; i < envNames.size(); i++) {
                String name = envNames.get(i);
                if (column + name.length() + 2 > 96) { // wrap rather than run off a narrow terminal
                    sb.append("\n").append(" ".repeat("  any of: ".length()));
                    column = "  any of: ".length();
                }
                sb.append(name).append(i < envNames.size() - 1 ? ", " : "");
                column += name.length() + 2;
            }
            sb.append("\n");
        }
        sb.append("\nStart pijota again once it is set. `pijota --list-models` shows what you can run.\n");
        sb.append("\nRunning a local model? Add a keyless provider in ").append(PijotaHome.resolve("settings.json"))
                                        .append(".");
        ProviderCatalog.providers().stream().filter(p -> p.oauth() != null).map(ProviderCatalog.Provider::id)
                                        .findFirst()
                                        .ifPresent(p -> sb.append("\nHave a ChatGPT subscription? `pijota login ")
                                                                        .append(p)
                                                                        .append("` signs in through the browser instead."));
        return sb.toString();
    }
}
