package org.pijota.tui;

import org.pijota.agent.PijotaSession;
import org.pijota.config.SettingsManager;
import org.pijota.extensions.UiContext;
import org.pijota.modes.SlashCommand;
import org.pijota.telegram.TelegramChat;
import org.pijota.telegram.TelegramClient;
import org.pijota.telegram.TelegramFrontend;
import org.pijota.util.QrTerminal;
import org.pijota.web.WebServer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Starts and stops an in-process remote-control channel — a web server or a Telegram bridge — attached to the LIVE
 * session, so the running TUI keeps working while a phone/browser drives the same conversation. One channel at a time.
 */
final class RemoteControl {

    /**
     * The one active channel: {@code summary} labels it; {@code onStop} tears it down; {@code info} is the re-showable
     * banner.
     */
    private record Channel(String summary, Runnable onStop, List<String> info) {
    }

    private final PijotaSession session;
    private final SettingsManager settings;
    private final Supplier<List<String>> statusLines;
    private final Supplier<List<SlashCommand>> commands;
    private final UiContext primaryUi; // the local TUI — restored as the extension UI owner when a channel stops

    private volatile Channel active; // written from the start/stop worker thread, read from the input/render threads

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

    /** The active channel's connection banner (URL/QR/link), so {@code /remote-control} can re-show it on demand. */
    List<String> info() {
        return active == null ? List.of() : active.info();
    }

    /** Browser remote control: LAN bind + pairing token, attached to the live session. */
    List<String> startWeb() {
        if (active != null)
            return List.of("remote control already active. Stop it first");
        String token = WebServer.newPairingToken();
        int port = WebServer.DEFAULT_PORT;
        var server = new WebServer(session, "0.0.0.0", port, session.llm(), statusLines, commands.get(), token);
        try {
            server.start();
        } catch (Exception e) {
            return List.of("could not start web remote control: " + e.getMessage());
        }
        var out = new ArrayList<String>();
        out.add("🔗 Remote control (web) is live on this session, token-gated.");
        out.add("   Anyone who can reach this host AND has the token can drive the agent (it runs shell + edits files).");
        out.addAll(WebServer.accessBanner("0.0.0.0", port, token));
        out.add("   /remote-control → re-show this link & QR, or stop.");
        activate(server.ui(), "web :" + port, server::close, out);
        return out;
    }

    /** Telegram remote control on a background poll thread. Requires the bot token + chat id in the env. */
    List<String> startTelegram() {
        if (active != null)
            return List.of("remote control already active. Stop it first");
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
        try {
            client.setMyCommands(TelegramChat.menuCommands(commands.get())); // / autocomplete menu, like --telegram
        } catch (RuntimeException ignored) {
            // best-effort: the bot still works without a registered menu
        }
        var out = new ArrayList<String>();
        out.add("🔗 Remote control (Telegram) is live. Drive this session from chat " + allowed + ".");
        if (username != null)
            // ?start=pijota → opening the link/QR sends "/start pijota", so the bot greets with a connection
            // confirmation
            out.addAll(QrTerminal.scannable("Scan to open the bot on your phone:",
                                            "https://t.me/" + username + "?start=pijota"));
        else
            out.add("   (couldn't fetch the bot's @username via getMe, no scannable link; check the token/network)");
        out.add("   /remote-control → re-show this link & QR, or stop.");
        activate(chat.ui(), "telegram " + (username != null ? "@" + username : String.valueOf(allowed)), () -> {
            frontend.stop();
            chat.ui().cancelPending(); // unblock any tool waiting on a tap/prompt so the turn can't hang
        }, out);
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
        return List.of(label + " · remote control stopped");
    }

    /** Record the channel and route extension-command UI (e.g. {@code /model}'s picker) to it while it drives. */
    private void activate(UiContext channelUi, String summary, Runnable onStop, List<String> info) {
        active = new Channel(summary, onStop, List.copyOf(info));
        session.extensionRegistry().setTui(channelUi);
    }
}
