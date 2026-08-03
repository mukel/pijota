package org.pijota;

import org.pijota.acp.AcpFileTool;
import org.pijota.acp.AcpFrontend;
import org.pijota.acp.AcpPermissionExtension;
import org.pijota.agent.HeadlessAgentRunner;
import org.pijota.agent.SessionExporter;
import org.pijota.agent.SessionManager;
import org.pijota.cli.ArgParser;
import org.pijota.cli.Args;
import org.pijota.cli.AuthCommand;
import org.pijota.cli.FileProcessor;
import org.pijota.cli.LicenseCommand;
import org.pijota.config.OAuthLogin;
import org.pijota.config.OAuthTokenStore;
import org.pijota.config.ProviderCatalog;
import org.pijota.config.SettingsManager;
import org.pijota.llm.ModelDisplay;
import org.pijota.llm.ModelRegistry;
import org.pijota.llm.SetupRequiredException;
import org.pijota.modes.PrintMode;
import org.pijota.modes.RpcMode;
import org.pijota.telegram.TelegramChat;
import org.pijota.telegram.TelegramClient;
import org.pijota.telegram.TelegramFrontend;
import org.pijota.tui.AppendOnlyTui;
import org.pijota.util.CliErrorReporter;
import org.pijota.util.PijotaHome;
import org.pijota.util.Platform;
import org.pijota.util.SelfRespawn;
import org.pijota.util.StartupTimer;
import org.pijota.util.Util;
import org.pijota.web.WebServer;
import org.pijota.worktree.WorktreeCommand;

import ch.qos.logback.classic.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Main {
    static {
        // Silence logback's startup status BEFORE the first logger (below) initializes the context. The console
        // belongs to the TUI / CliErrorReporter (see logback.xml). Set as a system property, not a <statusListener> in
        // logback.xml, because these messages are emitted during logback's bootstrap, before it parses the config: a
        // native image built from a shaded jar can't read logback's version from the merged manifest, WARNs about it,
        // and then dumps its whole status trail to stdout. This installs a no-op listener first, so nothing prints.
        if (System.getProperty("logback.statusListenerClass") == null)
            System.setProperty("logback.statusListenerClass", "ch.qos.logback.core.status.NopStatusListener");
    }

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    // SIGHUP is deliberately left at its default (terminate): closing a terminal ends the session it was running,
    // which is what every other terminal program does. Surviving the hangup is a job for nohup/setsid/tmux, and
    // ignoring it stranded `pijota web` holding its port with no way left to Ctrl-C it.

    public static void main(String[] args) {
        int exitCode = run(args);
        System.exit(exitCode);
    }

    /**
     * {@code --verbose}: the same thing {@code PIJOTA_LOG_LEVEL=DEBUG} does, which is the only meaning "verbose" can
     * carry here -- the log file is the sole sink by design (see logback.xml), so there is no second, chattier console
     * to turn on. Set through logback's API rather than the property the config reads, because logback has already
     * configured itself by the time argv is parsed: this class's own logger is a static field.
     *
     * <p>
     * The flag was parsed and stored and nothing ever read it, so {@code --verbose} was advertised in {@code --help}
     * and did nothing at all.
     */
    private static void enableDebugLogging() {
        try {
            var root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            // Qualified on purpose: this is logback's Logger, and the file already speaks SLF4J's.
            if (root instanceof ch.qos.logback.classic.Logger logbackRoot)
                logbackRoot.setLevel(Level.DEBUG);
        } catch (Throwable ignored) {
            // Another SLF4J binding, or none: --verbose is a diagnostic aid, never a reason to fail to start.
        }
    }

    static int run(String[] args) {
        try {
            SelfRespawn.storeArgs(args);
            if (args.length > 0 && "login".equals(args[0]))
                return login(args);
            if (args.length > 0 && "logout".equals(args[0]))
                return logout(args);
            if (args.length > 0 && "worktree".equals(args[0]))
                return WorktreeCommand.run(args);
            // Before ArgParser: `auth` has its own grammar and must work without resolving a model or building an LLM.
            if (args.length > 0 && AuthCommand.NAME.equals(args[0]))
                return AuthCommand.run(List.of(args).subList(1, args.length), new SettingsManager());

            var parsedArgs = ArgParser.parse(args);
            if (parsedArgs.verbose())
                enableDebugLogging();
            if (parsedArgs.help()) {
                ArgParser.printHelp();
                return 0;
            }
            if (parsedArgs.license())
                return LicenseCommand.run();
            if (parsedArgs.version()) {
                System.out.println("pijota v" + PijotaVersion.current());
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
        } catch (SetupRequiredException e) {
            // Not a crash: nothing is configured yet. Print the guidance plainly, without the failure framing.
            System.err.println(e.getMessage());
            return 1;
        } catch (Throwable e) {
            return CliErrorReporter.fatal("Pijota failed", e);
        }
    }

    /** Which frontend a set of args selects, in the order {@link #runFrontend} decides it. */
    enum Frontend {
        AGENT, TELEGRAM, WEB, ACP, RPC, PRINT, TUI
    }

    /**
     * The routing decision on its own, so the order can be asserted without a bootstrap.
     *
     * <p>
     * The order is the whole content of this method, and it was wrong: {@code RPC} sat below {@code PRINT}, which
     * claims any redirected stdin. Since JSON-RPC arrives on stdin by definition, {@code --mode rpc} was unreachable in
     * the only way it can be driven -- the request lines were concatenated into a prompt and answered in prose -- and
     * reachable only from a terminal, where nothing can speak to it.
     */
    static Frontend frontendFor(Args args, boolean stdinRedirected) {
        if (args.agent())
            return Frontend.AGENT;
        if (args.extensionFlags().containsKey("telegram"))
            return Frontend.TELEGRAM;
        if (args.extensionFlags().containsKey("web"))
            return Frontend.WEB;
        if (args.extensionFlags().containsKey("acp"))
            return Frontend.ACP;
        if (args.outputMode() == Args.OutputMode.RPC)
            return Frontend.RPC;
        if (args.print() || !args.promptArgs().isEmpty() || stdinRedirected)
            return Frontend.PRINT;
        return Frontend.TUI;
    }

    private static int runFrontend(Args parsedArgs) throws Exception {
        Warmup.start(); // overlap tool class-loading with the Jackson cold-start during bootstrap
        StartupTimer.mark("warmup started");
        var b = AppBootstrap.bootstrap(parsedArgs);
        StartupTimer.mark("bootstrap returned");

        if (parsedArgs.agent())
            return HeadlessAgentRunner.run(b.llm(), b.extensions());

        if (parsedArgs.extensionFlags().containsKey("telegram"))
            return runTelegram(b);

        if (parsedArgs.extensionFlags().containsKey("web"))
            return runWeb(b, parsedArgs.extensionFlags().get("web"));

        if (parsedArgs.extensionFlags().containsKey("acp"))
            return runAcp(b);

        // Before the print branch, not after it. RPC speaks JSON-RPC on stdin, so stdin is always a pipe when the
        // mode is used at all -- and the print branch claims any redirected stdin. That ordering made `--mode rpc`
        // unreachable in the only way it can be driven: the request lines were concatenated into a prompt and sent to
        // the model, which answered them in prose. The mode was only reachable from a terminal, where nothing can
        // speak to it.
        if (parsedArgs.outputMode() == Args.OutputMode.RPC) {
            RpcMode.run(b.session()::prompt);
            return 0;
        }

        if (parsedArgs.print() || !parsedArgs.promptArgs().isEmpty() || stdinRedirected()) {
            // Piped stdin and a prompt argument compose: the pipe carries the data, the argument the instruction
            // (`cat error.log | pj -p "what broke?"`). Reading stdin only when no prompt argument was given would
            // silently drop the piped content.
            String argPrompt = String.join(" ", parsedArgs.promptArgs());
            String piped = stdinRedirected() ? Util.readStdin() : "";
            String raw = piped.isBlank() ? argPrompt : argPrompt.isBlank() ? piped : piped + "\n\n" + argPrompt;
            String prompt = FileProcessor.processFileReferences(parsedArgs.fileArgs(), raw);
            if (prompt == null || prompt.isBlank())
                return CliErrorReporter.fatal("No prompt provided");
            return PrintMode.run(prompt, b.session(), parsedArgs.outputMode());
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
        tui.setJobRegistry(b.jobs()); // enables ctrl+b ctrl+b; always present, --no-jobs only takes the model's tools
        tui.syncThinkingLevel(b.llm().thinkingLevel());
        if (parsedArgs.resume())
            tui.requestSessionPicker();
        SelfRespawn.markStartupHealthy(); // TUI constructed → a healthy startup (extensions ready in the background)
        // Defer extension readiness off the first-paint path: MCP server connections alone block up to ~2.5s, and no
        // onReady work is needed to draw the prompt. MCP registers its tools late (honored on the next turn) by design,
        // so the prompt appears immediately while servers connect in the background.
        Thread.ofPlatform().name("pijota-extensions-ready").daemon(true).start(b.extensions()::ready);
        StartupTimer.mark("tui ready");
        try {
            tui.run(PijotaVersion.current(), b.cwd());
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
        // Defer extension readiness (MCP connects block up to ~2.5s) so the bot starts polling immediately.
        Thread.ofPlatform().name("pijota-extensions-ready").daemon(true).start(b.extensions()::ready);

        try {
            client.setMyCommands(TelegramChat.menuCommands(b.extensions().slashCommands()));
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
        // Secure by default on the network: a non-loopback bind is token-gated (the agent runs shell + edits files, so
        // an open LAN endpoint is dangerous). Loopback stays open; only the local user can reach it.
        String token = addr.isLoopback() ? null : WebServer.newPairingToken();
        var server = new WebServer(session, addr.host(), addr.port(), b.llm(), b.extensions()::statusLines,
                                        b.extensions().slashCommands(), token);
        b.extensions().setTui(server.ui());
        // Defer extension readiness (MCP connects block up to ~2.5s) so the server starts serving immediately.
        Thread.ofPlatform().name("pijota-extensions-ready").daemon(true).start(b.extensions()::ready);
        try {
            server.start();
            SelfRespawn.markStartupHealthy();
            System.out.println("[web] pijota web ui on http://" + addr.host() + ":" + addr.port()
                                            + " (Ctrl-C to stop)");
            if (token != null)
                System.out.println("[web] non-loopback bind, token-gated; scan to pair:");
            WebServer.accessBanner(addr.host(), addr.port(), token).forEach(System.out::println);
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

    private static int runAcp(AppBootstrap.Result b) throws Exception {
        var session = b.session();
        // No interactive selector over the wire in v1; the /question tool would block with no UI to answer it.
        b.tools().unregister("question");
        var frontend = new AcpFrontend(session, b.extensions().slashCommands(), b.llm().thinkingLevel());
        b.extensions().setTui(frontend.ui());
        // Gate mutating tools (write/edit/apply_patch/bash) behind the client's approval prompt.
        b.extensions().register(new AcpPermissionExtension(frontend::requestToolPermission));
        // Route read/write through the client's filesystem when it advertises the capability (editor buffers).
        b.tools().get("read").ifPresent(t -> b.tools().register(AcpFileTool.readOverride(t, frontend)));
        b.tools().get("write").ifPresent(t -> b.tools().register(AcpFileTool.writeOverride(t, frontend)));
        // Defer extension readiness (MCP connects block up to ~2.5s) so the agent answers the handshake immediately.
        Thread.ofPlatform().name("pijota-extensions-ready").daemon(true).start(b.extensions()::ready);
        SelfRespawn.markStartupHealthy();
        System.err.println("[acp] Pijota ACP mode active on stdio. Waiting for an editor client...");
        try {
            frontend.run(); // blocks reading stdin until the client disconnects (EOF)
            return 0;
        } finally {
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

    /**
     * {@code --help} is universal, and every other subcommand honours it -- {@code login} took it as a provider name
     * and opened an interactive API-key prompt for a provider called "--help", synthesising {@code __HELP_API_KEY}.
     */
    private static boolean wantsHelp(String[] args) {
        return args.length > 1 && (args[1].equals("--help") || args[1].equals("-h"));
    }

    private static void printAuthUsage() {
        System.out.println("USAGE: pijota login [provider] [model]");
        System.out.println("       pijota logout [provider]");
        System.out.println();
        System.out.println("With no provider, both act on \"kimi-coding\".");
        System.out.println("A provider with OAuth starts a device login; otherwise you are asked for an API key.");
        System.out.println("`pijota --list-models` shows the providers with a usable credential.");
    }

    private static int login(String[] args) throws Exception {
        if (wantsHelp(args)) {
            printAuthUsage();
            return 0;
        }
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
        if (wantsHelp(args)) {
            printAuthUsage();
            return 0;
        }
        String provider = args.length > 1 ? args[1] : "kimi-coding";
        var settings = new SettingsManager();
        // It reported a removal either way, so `pijota logout typo` cheerfully confirmed deleting a credential that
        // never existed -- and left the real one in place under its correct name.
        boolean had = settings.config().apiKeys() != null && settings.config().apiKeys().containsKey(provider);
        settings.removeApiKey(provider);
        System.out.println(had
                                        ? "Removed stored API key for " + provider + "."
                                        : "No stored API key for " + provider + "; nothing to remove.");
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
            return new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            return null;
        }
    }

    private static void listModels(String filter) {
        // Only models you can actually run: their provider has a key/OAuth token (or is keyless), matching every
        // frontend's /model picker. The catalog is far larger; the footer notes how many more need a key.
        var models = ModelRegistry.search(ModelRegistry.availableModels(new SettingsManager()), filter);
        if (models.isEmpty()) {
            System.out.println("No usable models"
                                            + (filter == null || filter.isBlank() ? "" : " matching \"" + filter + "\"")
                                            + ". Set a provider API key (env or ~/.pijota/settings.json) or run"
                                            + " `pijota login`.");
            return;
        }
        System.out.println("Available models:");
        for (var m : models)
            System.out.printf("  %-45s [%-12s] context: %-5s input: %-14s price: %s%n", m.provider() + "/" + m.id(),
                                            m.provider(), ModelDisplay.context(m), ModelDisplay.modalities(m),
                                            ModelDisplay.pricing(m));
        int total = ModelRegistry.search(ModelRegistry.KNOWN_MODELS, filter).size();
        System.out.printf("%n%d of %d model(s) usable (the rest need a provider API key)%n", models.size(), total);
    }

    private static boolean stdinRedirected() {
        // Ask what stdin IS, not whether bytes have arrived: the native binary starts in ~10ms and can beat the
        // pipe's writer, so available()==0 races on `echo x | pj`. On Linux /proc names the fd's target; a pipe or
        // file means redirected (readStdin then blocks until EOF and gets everything), a tty means interactive.
        try {
            var fd0 = Path.of("/proc/self/fd/0");
            if (Files.exists(fd0)) {
                String target = Files.readSymbolicLink(fd0).toString();
                return !target.startsWith("/dev/pts") && !target.startsWith("/dev/tty");
            }
        } catch (Exception ignored) {
            // fall through to the console probes on platforms without /proc
        }
        Console console = System.console();
        if (console == null) {
            try {
                return System.in.available() > 0;
            } catch (Exception e) {
                return true;
            }
        }
        try {
            // Java 22+ hands out a Console even with redirected streams; only isTerminal() tells them apart.
            return !(boolean) Console.class.getMethod("isTerminal").invoke(console);
        } catch (Exception e) {
            return false; // pre-22: a non-null console means an interactive terminal
        }
    }

    private static boolean shouldSupervise(Args args) {
        return !Platform.isNativeImage() && !args.agent() && !args.print() && args.promptArgs().isEmpty()
                                        && args.outputMode() != Args.OutputMode.RPC && !stdinRedirected();
    }

}
