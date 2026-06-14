package ai.pijota;

import ai.pijota.agent.HeadlessAgentRunner;
import ai.pijota.agent.SessionExporter;
import ai.pijota.agent.SessionManager;
import ai.pijota.cli.ArgParser;
import ai.pijota.cli.Args;
import ai.pijota.cli.FileProcessor;
import ai.pijota.config.OAuthLogin;
import ai.pijota.config.OAuthTokenStore;
import ai.pijota.config.SettingsManager;
import ai.pijota.llm.ModelRegistry;
import ai.pijota.config.ProviderCatalog;
import ai.pijota.modes.PrintMode;
import ai.pijota.modes.RpcMode;
import ai.pijota.tui.AppendOnlyTui;
import ai.pijota.util.PijotaHome;
import ai.pijota.util.SelfRespawn;

import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    private static final String VERSION = "0.4.0";

    static {
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("HUP"), sun.misc.SignalHandler.SIG_IGN);
        } catch (Throwable e) {
            System.err.println("[pijota] SIGHUP ignore failed: " + e);
        }
    }

    public static void main(String[] args) {
        try {
            SelfRespawn.storeArgs(args);
            if (args.length > 0 && "login".equals(args[0])) {
                login(args);
                return;
            }
            if (args.length > 0 && "logout".equals(args[0])) {
                logout(args);
                return;
            }
            var parsedArgs = ArgParser.parse(args);
            if (parsedArgs.help()) {
                ArgParser.printHelp();
                return;
            }
            if (parsedArgs.version()) {
                System.out.println("pijota v" + VERSION);
                return;
            }
            if (parsedArgs.listModels()) {
                listModels(parsedArgs.listModelsFilter());
                return;
            }
            if (parsedArgs.exportPath() != null) {
                exportSession(parsedArgs);
                return;
            }
            if (!parsedArgs.worker() && shouldSupervise(parsedArgs)) {
                System.exit(Supervisor.run(args));
            }
            run(parsedArgs);
        } catch (Throwable e) {
            e.printStackTrace(System.err);
            System.err.flush();
            System.exit(1);
        }
    }

    private static void run(Args parsedArgs) throws Exception {
        var b = AppBootstrap.bootstrap(parsedArgs);

        if (parsedArgs.agent()) {
            int ec = HeadlessAgentRunner.run(b.llm(), b.extensions());
            System.exit(ec);
        }

        if (parsedArgs.print() || !parsedArgs.promptArgs().isEmpty() || stdinRedirected()) {
            String raw = !parsedArgs.promptArgs().isEmpty() ? String.join(" ", parsedArgs.promptArgs()) : readStdin();
            String prompt = FileProcessor.processFileReferences(parsedArgs.fileArgs(), raw);
            if (prompt == null || prompt.isBlank()) {
                System.err.println("No prompt.");
                System.exit(1);
            }
            int ec = PrintMode.run(prompt, b.session().agentLoop(), parsedArgs.outputMode());
            System.exit(ec);
        }

        if (parsedArgs.outputMode() == Args.OutputMode.RPC) {
            RpcMode.run(b.session()::prompt);
            return;
        }

        var theme = ThemeLoader.load(b.settings(), parsedArgs, b.cwd());
        var session = b.session();
        var tui = new AppendOnlyTui(session, theme, b.llm().provider(), b.llm().model(), b.extensions().slashCommands(),
                                        b.settings(), b.extensions().skills(), b.extensions()::statusLines,
                                        b.extensions().keybindings());
        b.extensions().setTui(tui);
        b.extensions().setSuspendResume(tui::suspendTui, tui::resumeTui);
        tui.syncThinkingLevel(b.llm().thinkingLevel());
        b.extensions().ready();
        if (parsedArgs.resume())
            tui.requestSessionPicker();
        try {
            tui.run(VERSION, b.cwd());
        } finally {
            b.extensions().cleanup();
            tui.close();
            session.close();
        }
    }

    private static void exportSession(Args args) throws Exception {
        Path sessionFile;
        if (args.sessionPath() != null) {
            sessionFile = Path.of(args.sessionPath());
        } else {
            var sessionDir = PijotaHome.resolve("sessions");
            var sm = new SessionManager(sessionDir);
            var cwd = System.getProperty("user.dir");
            sessionFile = sm.latestSession(cwd, null);
        }
        if (!Files.exists(sessionFile)) {
            System.err.println("Session file not found: " + sessionFile);
            System.exit(1);
        }
        String md = SessionExporter.toMarkdown(sessionFile);
        Path output = Path.of(args.exportPath());
        Files.writeString(output, md);
        System.out.println("Exported to " + output);
    }

    private static void login(String[] args) throws Exception {
        String provider = args.length > 1 ? args[1] : "kimi-coding";
        String model = args.length > 2 ? args[2] : ModelRegistry.defaultModelForProvider(provider);
        var settings = new SettingsManager();

        var oauth = ProviderCatalog.oauth(provider);
        if (oauth.isPresent()) {
            var o = oauth.get();
            System.out.println("Starting OAuth login for " + provider + "...");
            OAuthLogin.TokenResult result;
            if (o.oauthTokenEndpoint() != null && !o.oauthTokenEndpoint().isBlank()) {
                result = OAuthLogin.loginOpenAI(o.deviceEndpoint(), o.tokenEndpoint(), o.oauthTokenEndpoint(),
                                                o.clientId());
            } else {
                result = OAuthLogin.login(o.deviceEndpoint(), o.tokenEndpoint(), o.clientId(), o.scope());
            }
            String tokenUrl = o.oauthTokenEndpoint() != null && !o.oauthTokenEndpoint().isBlank()
                                            ? o.oauthTokenEndpoint()
                                            : o.tokenEndpoint();
            var store = new OAuthTokenStore();
            store.save(provider, result.accessToken(), result.refreshToken(), result.expiresAt(), result.accountId(),
                                            tokenUrl, o.clientId());
            settings.setDefaultModelForProvider(provider, model);
            System.out.println("Logged in to " + provider + " using default model " + model + ".");
            return;
        }

        String key = readSecret("API key for " + provider + " (" + settings.apiKeyHint(provider) + "): ");
        if (key == null || key.isBlank()) {
            System.err.println("No API key entered.");
            System.exit(1);
        }
        settings.saveApiKey(provider, key.strip(), model);
        System.out.println("Logged in to " + provider + " using default model " + model + ".");
    }

    private static void logout(String[] args) throws Exception {
        String provider = args.length > 1 ? args[1] : "kimi-coding";
        var settings = new SettingsManager();
        settings.removeApiKey(provider);
        System.out.println("Removed stored API key for " + provider + ".");
    }

    private static String readSecret(String prompt) {
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword(prompt);
            return chars == null ? null : new String(chars);
        }
        System.err.print(prompt);
        try {
            return new String(System.in.readAllBytes()).strip();
        } catch (Exception e) {
            return null;
        }
    }

    private static void listModels(String filter) {
        var models = ModelRegistry.filter(filter);
        System.out.println("Available models:");
        for (var m : models)
            System.out.printf("  %-45s [%-12s] context: %dK%n", m.provider() + "/" + m.id(), m.provider(),
                                            m.contextWindow() / 1024);
        System.out.println("\n" + models.size() + " model(s)");
    }

    private static boolean stdinRedirected() {
        return System.console() == null;
    }

    private static boolean shouldSupervise(Args args) {
        return !args.agent() && !args.print() && args.promptArgs().isEmpty() && args.outputMode() != Args.OutputMode.RPC
                                        && !stdinRedirected();
    }

    private static String readStdin() {
        try {
            return new String(System.in.readAllBytes()).strip();
        } catch (Exception ignored) {}
        return null;
    }
}
