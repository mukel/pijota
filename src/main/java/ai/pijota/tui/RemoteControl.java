package ai.pijota.tui;

import ai.pijota.agent.PijotaSession;
import ai.pijota.config.SettingsManager;
import ai.pijota.extensions.UiContext;
import ai.pijota.modes.SlashCommand;
import ai.pijota.telegram.TelegramChat;
import ai.pijota.telegram.TelegramClient;
import ai.pijota.telegram.TelegramFrontend;
import ai.pijota.util.QrTerminal;
import ai.pijota.web.WebServer;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;

/**
 * Starts and stops an in-process remote-control channel — a web server or a Telegram bridge — attached to the LIVE
 * session, so the running TUI keeps working while a phone/browser drives the same conversation. One channel at a time.
 */
final class RemoteControl {
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The one active channel: {@code summary} labels it in the selector; {@code onStop} tears it down. */
    private record Channel(String summary, Runnable onStop) {
    }

    private final PijotaSession session;
    private final SettingsManager settings;
    private final Supplier<List<String>> statusLines;
    private final Supplier<List<SlashCommand>> commands;
    private final UiContext primaryUi; // the local TUI — restored as the extension UI owner when a channel stops

    private Channel active;

    RemoteControl(PijotaSession session, SettingsManager settings, Supplier<List<String>> statusLines,
                                    Supplier<List<SlashCommand>> commands, UiContext primaryUi) {
        this.session = session;
        this.settings = settings;
        this.statusLines = statusLines;
        this.commands = commands;
        this.primaryUi = primaryUi;
    }

    boolean active() {
        return active != null;
    }

    String summary() {
        return active == null ? null : active.summary();
    }

    /** Browser remote control: LAN bind + pairing token, attached to the live session. */
    List<String> startWeb() {
        if (active != null)
            return List.of("remote control already active — stop it first");
        String token = newToken();
        int port = WebServer.DEFAULT_PORT;
        var server = new WebServer(session, "0.0.0.0", port, session.llm(), statusLines, commands.get(), token);
        try {
            server.start();
        } catch (Exception e) {
            return List.of("could not start web remote control: " + e.getMessage());
        }
        activate(server.ui(), "web :" + port, server::close);
        var out = new ArrayList<String>();
        out.add("🔗 Remote control (web) is live on this session — token-gated.");
        out.add("   Anyone who can reach this host AND has the token can drive the agent (it runs shell + edits files).");
        out.addAll(WebServer.accessBanner("0.0.0.0", port, token));
        out.add("   /remote-control → Stop to close it.");
        return out;
    }

    /** Telegram remote control on a background poll thread. Requires the bot token + chat id in the env. */
    List<String> startTelegram() {
        if (active != null)
            return List.of("remote control already active — stop it first");
        String botToken = System.getenv("TELEGRAM_BOT_TOKEN");
        String chatIdEnv = System.getenv("TELEGRAM_CHAT_ID");
        if (botToken == null || botToken.isBlank())
            return List.of("Telegram remote control needs TELEGRAM_BOT_TOKEN set (and TELEGRAM_CHAT_ID).");
        if (chatIdEnv == null || chatIdEnv.isBlank())
            return List.of("Set TELEGRAM_CHAT_ID to the chat this bot serves (this build is single-chat).");
        long allowed;
        try {
            allowed = Long.parseLong(chatIdEnv.trim());
        } catch (NumberFormatException e) {
            return List.of("TELEGRAM_CHAT_ID must be a numeric chat id.");
        }
        var client = new TelegramClient(botToken);
        String username = client.getMyUsername(); // bot @username → a tappable/scannable t.me link
        var chat = new TelegramChat(allowed, session, client, settings);
        var frontend = new TelegramFrontend(client, id -> chat, id -> id == allowed);
        Thread thread = new Thread(frontend::run, "pijota-remote-telegram");
        thread.setDaemon(true);
        thread.start();
        activate(chat.ui(), "telegram " + (username != null ? "@" + username : String.valueOf(allowed)), frontend::stop);
        var out = new ArrayList<String>();
        out.add("🔗 Remote control (Telegram) is live — drive this session from chat " + allowed + ".");
        if (username != null)
            // ?start=pijota → opening the link/QR sends "/start pijota", so the bot greets with a connection confirmation
            out.addAll(QrTerminal.scannable("Scan to open the bot on your phone:",
                                            "https://t.me/" + username + "?start=pijota"));
        else
            out.add("   (couldn't fetch the bot's @username via getMe — no scannable link; check the token/network)");
        out.add("   /remote-control → Stop to close it.");
        return out;
    }

    List<String> stop() {
        if (active == null)
            return List.of("no remote control active");
        String label = active.summary();
        try {
            active.onStop().run();
        } catch (Exception ignored) {
            // best effort — onStop detaches the session listener and stops the server/poll loop
        }
        active = null;
        session.extensionRegistry().setTui(primaryUi); // hand the extension UI back to the local terminal
        return List.of(label + " — remote control stopped");
    }

    /** Record the channel and route extension-command UI (e.g. {@code /model}'s picker) to it while it drives. */
    private void activate(UiContext channelUi, String summary, Runnable onStop) {
        active = new Channel(summary, onStop);
        session.extensionRegistry().setTui(channelUi);
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
