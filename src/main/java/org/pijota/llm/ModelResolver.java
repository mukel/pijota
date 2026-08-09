package org.pijota.llm;

import org.pijota.cli.Args;
import org.pijota.config.ProviderCatalog;
import org.pijota.config.SettingsManager;
import org.pijota.util.Cli;
import org.pijota.util.CliErrorReporter;
import org.pijota.util.PijotaHome;
import org.pijota.util.Util;

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

    /**
     * The four decisions a run's model comes out of, in the order they depend on each other: what was asked for, what
     * credential answers for it, whether that is enough to start, and whether the name looks like a typo.
     *
     * <p>
     * They were one cascade of 72 lines, which read as a single hard thing rather than four ordinary ones. The second
     * step can change the first step's answer -- detecting a provider from the environment switches the model with it
     * -- so each step takes a {@link Resolved} and gives one back, and the order below is the policy.
     */
    public static Resolved resolve(Args args, SettingsManager settings) {
        var chosen = chooseProviderAndModel(args, settings);
        var withCredential = attachCredential(chosen, args, settings);
        requireSomethingToRunWith(withCredential, args, settings);
        warnIfTheModelLooksLikeATypo(withCredential, args);
        return withCredential;
    }

    /** What was asked for, by flag or alias, with the two names that cannot be honoured rejected by name. */
    private static Resolved chooseProviderAndModel(Args args, SettingsManager settings) {
        // Per-role model: print/-p uses the "print" alias when set and no explicit --model was given, so
        // non-interactive runs can default to a cheaper/faster model (e.g. alias print -> alias/fast). ArgParser
        // splits "alias/<name>" into provider="alias" + model="<name>", so resolve the print role the same way rather
        // than passing a bare "alias/print" string (which resolveProviderModel would take literally).
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
        // Same reasoning one step out: a provider nobody has heard of used to reach the LLM client anyway, where the
        // missing base URL defaulted to OpenAI's. `-m nonexistent/model` with a DeepSeek key then failed with an
        // OpenAI error pointing at platform.openai.com, naming a provider the user never mentioned. User providers
        // from settings.json are already in the catalog by now, so an id still missing here is a typo or an invention.
        if (args.provider() != null && !args.provider().isBlank()
                                        && ProviderCatalog.find(resolved.provider()).isEmpty())
            throw new IllegalStateException("Unknown provider '" + resolved.provider() + "'. Run `" + Cli.name()
                                            + " --list-models` for the ones you can reach, or define it under "
                                            + "\"providers\" in " + PijotaHome.resolve("settings.json") + ".");
        return new Resolved(resolved.provider(), resolved.model(), args.apiKey());
    }

    /**
     * The credential that answers for the chosen provider: the flag, else what is stored or in the environment for it.
     *
     * <p>
     * Failing that, and only when no provider was named, whichever provider the environment does have a key for --
     * which switches the model too, or the detected provider would be run with the previous one's model (openai-codex
     * with "gpt-4o").
     */
    private static Resolved attachCredential(Resolved chosen, Args args, SettingsManager settings) {
        if (chosen.apiKey() != null && !chosen.apiKey().isBlank())
            return chosen;
        String provider = chosen.provider();
        String model = chosen.model();
        String apiKey = provider != null && !provider.isBlank() ? settings.resolveApiKey(provider) : chosen.apiKey();
        if (hasUsableKey(apiKey, provider) || args.provider() != null)
            return new Resolved(provider, model, apiKey);

        String detected = settings.detectProviderFromEnv();
        if (detected == null)
            return new Resolved(provider, model, apiKey);
        if (!ModelRegistry.isKnownProviderModel(detected, model))
            model = ModelRegistry.defaultModelForProvider(detected);
        return new Resolved(detected, model, settings.resolveApiKey(detected));
    }

    /** A blank key is fine for a provider that needs none -- a local server, or anything keyless. */
    private static boolean hasUsableKey(String apiKey, String provider) {
        return apiKey != null && !(apiKey.isBlank() && SettingsManager.requiresApiKey(provider));
    }

    /**
     * Nothing to run with: either this is a first run and nothing is configured, or one specific key is missing.
     *
     * <p>
     * Onboard only when the user asked for nothing in particular AND nothing is configured. Someone who typed
     * --provider/--model made a choice and wants to know what is wrong with THAT choice, not a tour of how to get
     * started. {@code --agent} is exempt: it is handed its credential elsewhere.
     */
    private static void requireSomethingToRunWith(Resolved resolved, Args args, SettingsManager settings) {
        if (hasUsableKey(resolved.apiKey(), resolved.provider()) || args.agent())
            return;
        boolean chosen = args.provider() != null || args.model() != null;
        if (!chosen && ModelRegistry.availableModels(settings).isEmpty())
            throw new SetupRequiredException(firstRunMessage());
        throw new IllegalStateException(missingApiKeyMessage(resolved.provider(), resolved.model(), settings));
    }

    /**
     * An explicitly chosen model that is not in a catalogued provider's list is usually a typo. Say so up front rather
     * than letting the provider API reject it after a wasted round-trip. Non-fatal: new and uncatalogued models still
     * pass straight through.
     */
    private static void warnIfTheModelLooksLikeATypo(Resolved resolved, Args args) {
        if (args.model() == null || ModelRegistry.isKnownProviderModel(resolved.provider(), resolved.model())
                                        || ModelRegistry.forProvider(resolved.provider()).isEmpty())
            return;
        ModelRegistry.closestModel(resolved.provider(), resolved.model())
                                        .ifPresent(suggestion -> CliErrorReporter.warn("'" + resolved.model()
                                                                        + "' is not a known " + resolved.provider()
                                                                        + " model. Did you mean '" + suggestion
                                                                        + "'? Proceeding anyway."));
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
            sb.append(" Or run `").append(Cli.name()).append(" login ").append(provider).append("`.");
        sb.append(" Use `").append(Cli.name()).append(" --list-models ").append(provider)
                                        .append("` to see available models.");
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

        var sb = new StringBuilder();
        sb.append(Util.wrap("No provider configured yet. Set an API key for any provider:", width(), ""))
                                        .append("\n\n");
        sb.append("  export ").append(envNames.isEmpty() ? "ANTHROPIC_API_KEY" : envNames.get(0)).append("=…\n\n");
        if (envNames.size() > 1) {
            String label = "  any of: ";
            sb.append(label);
            int column = label.length();
            for (int i = 0; i < envNames.size(); i++) {
                String name = envNames.get(i);
                if (column + name.length() + 2 > width()) {
                    sb.append("\n").append(" ".repeat(label.length()));
                    column = label.length();
                }
                sb.append(name).append(i < envNames.size() - 1 ? ", " : "");
                column += name.length() + 2;
            }
            sb.append("\n");
        }
        sb.append("\n").append(Util.wrap(
                                        "Start pijota again once it is set. `" + Cli.name()
                                                                        + " --list-models` shows what you can run.",
                                        width(), "")).append("\n");
        sb.append("\n").append(Util.wrap(
                                        "Running a local model? Add a keyless provider in "
                                                                        + PijotaHome.resolve("settings.json") + ".",
                                        width(), ""));
        ProviderCatalog.providers().stream().filter(p -> p.oauth() != null).map(ProviderCatalog.Provider::id)
                                        .findFirst()
                                        .ifPresent(p -> sb.append("\n").append(Util.wrap(
                                                                        "Have a ChatGPT subscription? `" + Cli.name()
                                                                                                        + " login " + p
                                                                                                        + "` signs in through the browser instead.",
                                                                        width(), "")));
        return sb.toString();
    }

    /**
     * The width this screen is written to: the terminal's, shared with the model list so the two agree.
     *
     * <p>
     * It used to be a fixed 96, which is not a width anyone's terminal is, so on the classic 80 the list of variables
     * broke in the middle of five of them, and this is the first screen pijota ever shows a person.
     */
    private static int width() {
        return Util.terminalWidth();
    }

}
