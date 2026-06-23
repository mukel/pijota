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
import ai.pijota.telegram.TelegramChat;
import ai.pijota.telegram.TelegramClient;
import ai.pijota.telegram.TelegramFrontend;
import ai.pijota.web.WebServer;
import ai.pijota.tui.AppendOnlyTui;
import ai.pijota.util.CliErrorReporter;
import ai.pijota.util.PijotaHome;
import ai.pijota.util.Platform;
import ai.pijota.util.SelfRespawn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String VERSION = "0.4.0";

    static {
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("HUP"), sun.misc.SignalHandler.SIG_IGN);
        } catch (Throwable e) {
            logger.warn("Failed to ignore SIGHUP", e);
        }
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        System.exit(exitCode);
    }

    static int run(String[] args) {
        try {
            SelfRespawn.storeArgs(args);
            if (args.length > 0 && "login".equals(args[0]))
                return login(args);
            if (args.length > 0 && "logout".equals(args[0]))
                return logout(args);

            var parsedArgs = ArgParser.parse(args);
            if (parsedArgs.help()) {
                ArgParser.printHelp();
                return 0;
            }
            if (parsedArgs.version()) {
                System.out.println("pijota v" + VERSION);
                return 0;
            }
            if (parsedArgs.listModels()) {
                listModels(parsedArgs.listModelsFilter());
                return 0;
            }
            if (parsedArgs.exportPath() != null)
                return exportSession(parsedArgs);

            if (!parsedArgs.worker() && shouldSupervise(parsedArgs))
                return Supervisor.run(args);

            return runFrontend(parsedArgs);
        } catch (Throwable e) {
            return CliErrorReporter.fatal("Pijota failed", e);
        }
    }

    private static int runFrontend(Args parsedArgs) throws Exception {
        Warmup.start(); // overlap tool class-loading with the Jackson cold-start during bootstrap
        var b = AppBootstrap.bootstrap(parsedArgs);

        if (parsedArgs.agent())
            return HeadlessAgentRunner.run(b.llm(), b.extensions());

        if (parsedArgs.extensionFlags().containsKey("telegram"))
            return runTelegram(b);

        if (parsedArgs.extensionFlags().containsKey("web"))
            return runWeb(b, parsedArgs.extensionFlags().get("web"));

        if (parsedArgs.print() || !parsedArgs.promptArgs().isEmpty() || stdinRedirected()) {
            String raw = !parsedArgs.promptArgs().isEmpty() ? String.join(" ", parsedArgs.promptArgs()) : readStdin();
            String prompt = FileProcessor.processFileReferences(parsedArgs.fileArgs(), raw);
            if (prompt == null || prompt.isBlank())
                return CliErrorReporter.fatal("No prompt provided");
            return PrintMode.run(prompt, b.session().agentLoop(), parsedArgs.outputMode());
        }

        if (parsedArgs.outputMode() == Args.OutputMode.RPC) {
            RpcMode.run(b.session()::prompt);
            return 0;
        }

        return runTui(parsedArgs, b);
    }

    private static int runTui(Args parsedArgs, AppBootstrap.Result b) throws Exception {
        var theme = ThemeLoader.load(b.settings(), parsedArgs, b.cwd());
        var session = b.session();
        var tui = new AppendOnlyTui(session, theme, b.llm().provider(), b.llm().model(), b.extensions().slashCommands(),
                                        b.settings(), b.extensions().skills(), b.extensions()::statusLines,
                                        b.extensions().keybindings());
        b.extensions().setTui(tui);
        tui.syncThinkingLevel(b.llm().thinkingLevel());
        b.extensions().ready();
        if (parsedArgs.resume())
            tui.requestSessionPicker();
        SelfRespawn.markStartupHealthy(); // TUI fully constructed + extensions ready → a healthy startup
        try {
            tui.run(VERSION, b.cwd());
            return 0;
        } finally {
            b.extensions().cleanup();
            tui.close();
            session.close();
        }
    }

    private static int runTelegram(AppBootstrap.Result b) {
        String token = System.getenv("TELEGRAM_BOT_TOKEN");
        if (token == null || token.isBlank())
            return CliErrorReporter.fatal("Set TELEGRAM_BOT_TOKEN to run the Telegram bot (--telegram)");

        String chatIdEnv = System.getenv("TELEGRAM_CHAT_ID");
        if (chatIdEnv == null || chatIdEnv.isBlank())
            return CliErrorReporter.fatal("Set TELEGRAM_CHAT_ID to the chat this bot serves (this build is single-chat)");

        long allowedChat = Long.parseLong(chatIdEnv.trim());
        var client = new TelegramClient(token);
        var session = b.session();
        var chat = new TelegramChat(allowedChat, session, client, b.settings());
        b.extensions().setTui(chat.ui());
        b.extensions().ready();

        try {
            client.setMyCommands(TelegramChat.COMMANDS);
            client.setMyShortDescription("Your pijota coding agent, in chat.");
            client.setMyDescription("Pijota coding agent. Just type to chat; use /help for commands. "
                                            + "Long answers arrive as file attachments.");
        } catch (RuntimeException e) {
            logger.warn("Could not register Telegram bot metadata: {}", e.getMessage());
        }
        System.out.println("[telegram] bot started for chat " + allowedChat + "; polling…");
        var frontend = new TelegramFrontend(client, chatId -> chat, chatId -> chatId == allowedChat);
        SelfRespawn.markStartupHealthy();
        try {
            frontend.run();
            return 0;
        } finally {
            b.extensions().cleanup();
            session.close();
        }
    }

    private static int runWeb(AppBootstrap.Result b, String flagValue) {
        var addr = WebServer.parseAddress(flagValue);
        var session = b.session();
        b.tools().unregister("question");
        var server = new WebServer(session, addr.host(), addr.port(), b.llm(), b.extensions()::statusLines,
                                        b.extensions().slashCommands());
        b.extensions().setTui(server.ui());
        b.extensions().ready();
        try {
            server.start();
            SelfRespawn.markStartupHealthy();
            if (!addr.isLoopback())
                CliErrorReporter.warn("Web UI bound to " + addr.host()
                                                + " — anyone who can reach this address controls this machine. "
                                                + "Use authentication or a firewall.");
            System.out.println("[web] pijota web ui on http://" + addr.host() + ":" + addr.port()
                                            + " (Ctrl-C to stop)");
            Thread.currentThread().join();
            return 0;
        } catch (Exception e) {
            throw new RuntimeException("web server failed", e);
        } finally {
            server.close();
            b.extensions().cleanup();
            session.close();
        }
    }

    private static int exportSession(Args args) throws Exception {
        Path sessionFile;
        if (args.sessionPath() != null) {
            sessionFile = Path.of(args.sessionPath());
        } else {
            var sessionDir = PijotaHome.resolve("sessions");
            var sm = new SessionManager(sessionDir);
            var cwd = System.getProperty("user.dir");
            sessionFile = sm.latestSession(cwd, null);
        }
        if (!Files.exists(sessionFile))
            return CliErrorReporter.fatal("Session file not found: " + sessionFile);

        String md = SessionExporter.toMarkdown(sessionFile);
        Path output = Path.of(args.exportPath());
        Files.writeString(output, md);
        System.out.println("Exported to " + output);
        return 0;
    }

    private static int login(String[] args) throws Exception {
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
                                                o.clientId(), System.out::println);
            } else {
                result = OAuthLogin.login(o.deviceEndpoint(), o.tokenEndpoint(), o.clientId(), o.scope(),
                                                System.out::println);
            }
            String tokenUrl = o.oauthTokenEndpoint() != null && !o.oauthTokenEndpoint().isBlank()
                                            ? o.oauthTokenEndpoint()
                                            : o.tokenEndpoint();
            var store = new OAuthTokenStore();
            store.save(provider, result.accessToken(), result.refreshToken(), result.expiresAt(), result.accountId(),
                                            tokenUrl, o.clientId());
            settings.setDefaultModelForProvider(provider, model);
            System.out.println("Logged in to " + provider + " using default model " + model + ".");
            return 0;
        }

        String key = readSecret("API key for " + provider + " (" + settings.apiKeyHint(provider) + "): ");
        if (key == null || key.isBlank())
            return CliErrorReporter.fatal("No API key entered");

        settings.saveApiKey(provider, key.strip(), model);
        System.out.println("Logged in to " + provider + " using default model " + model + ".");
        return 0;
    }

    private static int logout(String[] args) throws Exception {
        String provider = args.length > 1 ? args[1] : "kimi-coding";
        var settings = new SettingsManager();
        settings.removeApiKey(provider);
        System.out.println("Removed stored API key for " + provider + ".");
        return 0;
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
        if (System.console() != null)
            return false;
        try {
            return System.in.available() > 0;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean shouldSupervise(Args args) {
        return !Platform.isNativeImage() && !args.agent() && !args.print() && args.promptArgs().isEmpty()
                                        && args.outputMode() != Args.OutputMode.RPC && !stdinRedirected();
    }

    private static String readStdin() {
        try {
            return new String(System.in.readAllBytes()).strip();
        } catch (Exception ignored) {}
        return null;
    }
}
