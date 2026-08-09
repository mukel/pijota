package org.pijota.tui;

import org.pijota.agent.PijotaSession;
import org.pijota.config.SettingsManager;
import org.pijota.extensions.UiContext;
import org.pijota.modes.SlashCommand;
import org.pijota.telegram.TelegramChat;
import org.pijota.telegram.TelegramClient;
import org.pijota.telegram.TelegramFrontend;
import org.pijota.util.Executables;
import org.pijota.util.QrTerminal;
import org.pijota.web.WebServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Starts and stops an in-process remote-control channel, a web server or a Telegram bridge, attached to the LIVE
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
    private final UiContext primaryUi; // the local TUI, restored as the extension UI owner when a channel stops

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

    /** Browser remote control on the LAN: bind all interfaces + pairing token, attached to the live session. */
    List<String> startWeb() {
        return startWeb(Tunnel.NONE);
    }

    /**
     * Browser remote control reachable from anywhere: loopback bind (the tunnel, not the LAN, is the exposure) plus a
     * {@code cloudflared} quick tunnel in front. The trycloudflare URL is public; TLS ends at Cloudflare's edge, which
     * is in the trust path -- the pairing token is still required on every request.
     */
    List<String> startWebTunnel() {
        if (!Executables.isAvailable("cloudflared"))
            return missingTool("cloudflared", "https://github.com/cloudflare/cloudflared",
                                            "Debian/Ubuntu: sudo apt install cloudflared   (or grab the .deb from the link above)",
                                            "Fedora:        sudo dnf install cloudflared",
                                            "Arch:          sudo pacman -S cloudflared   (or an AUR helper: yay -S cloudflared)",
                                            "macOS:         brew install cloudflared",
                                            "Windows:       winget install --id Cloudflare.cloudflared");
        return startWeb(Tunnel.CLOUDFLARE);
    }

    /**
     * Browser remote control over Tailscale: loopback bind plus a foreground {@code tailscale serve} child proxying
     * {@code https://<node>.<tailnet>.ts.net} to it. Tailnet-only (no public URL, no third-party edge), WireGuard
     * encrypted, and serve does not buffer the event stream the way Cloudflare's free edge does. The child is killed on
     * stop, so the mapping dies with pijota.
     */
    List<String> startTailscale() {
        if (!Executables.isAvailable("tailscale"))
            return missingTool("tailscale", "https://tailscale.com/download",
                                            "Debian/Ubuntu: curl -fsSL https://tailscale.com/install.sh | sh   (or apt install tailscale)",
                                            "Fedora:        sudo dnf install tailscale && sudo systemctl enable --now tailscaled",
                                            "Arch:          sudo pacman -S tailscale && sudo systemctl enable --now tailscaled",
                                            "macOS:         brew install tailscale   (or the App Store app), then start it",
                                            "Windows:       winget install --id Tailscale.Tailscale   (or the .msi from the link above)");
        // tailscaled must be up and logged in: `tailscale serve` configures the daemon, so a dead daemon or a
        // logged-out node means the child starts but never proxies. Check before spawning so the error names the real
        // cause.
        if (!commandSucceeds("tailscale", "status", "--json")) {
            var out = new ArrayList<String>();
            out.add("tailscale is installed but tailscaled isn't reachable.");
            out.addAll(tailscaledDown());
            out.add("   Also needed once, for serve without root:  sudo tailscale set --operator=$USER");
            out.add("   And in the admin console (https://login.tailscale.com/admin): enable Serve + HTTPS certificates.");
            return out;
        }
        return startWeb(Tunnel.TAILSCALE);
    }

    /** A not-installed error that names the tool, links it, and gives a per-OS install one-liner. */
    static List<String> missingTool(String tool, String url, String... installLines) {
        var out = new ArrayList<String>();
        out.add("remote control needs " + tool + " installed. Install it, then retry:");
        for (String line : installLines)
            out.add("   " + line);
        out.add("   More: " + url);
        return out;
    }

    /**
     * What fronts the loopback web server, if anything. Each constant carries everything that differs between the
     * channels: the CLI name, the URL its child prints, the banner title, the security caveat (who is in the trust
     * path), and the failure signatures it can explain.
     *
     * <p>
     * Adding a channel is a constant plus a {@code start*} method plus a picker entry. The one dispatch left, which
     * child to spawn, is an exhaustive {@code switch}: the compiler names it, rather than a ternary quietly launching
     * the other kind's binary.
     */
    enum Tunnel {
        NONE("", null, "web", "", "0.0.0.0") { // no child; the LAN bind itself is the exposure
            @Override
            List<String> command(int port) {
                throw new IllegalStateException("the LAN bind has no child");
            }
        },
        CLOUDFLARE("cloudflared", "https://[a-z0-9-]+\\.trycloudflare\\.com", "web tunnel",
                                        "Public via cloudflared; TLS terminates at Cloudflare's edge, which can see the token URL.",
                                        WebServer.DEFAULT_HOST) {
            @Override
            List<String> command(int port) {
                // --no-autoupdate: the daemon must never self-replace mid-session (and the update check is startup
                // latency).
                return List.of("cloudflared", "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:" + port);
            }

            @Override
            List<String> diagnose(String output) {
                // Quick tunnels rate-limit by source IP (HTTP 429 / error 1015) and cloudflared exits without
                // retrying. The only fix is to wait it out or come from a different IP.
                if (output.contains("429") || output.contains("1015") || output.contains("rate limit"))
                    return List.of("   Cloudflare rate-limited this IP (error 1015). It clears on its own, usually within an hour.",
                                                    "   Retry later, or use the Tailscale channel instead (no third-party edge, no rate limit).");
                return null;
            }
        },
        // tailscale serve prints the address with a trailing slash; the pattern stops short of it so "?t=" appends
        // cleanly.
        TAILSCALE("tailscale", "https://[a-z0-9-]+\\.[a-z0-9-]+\\.ts\\.net", "tailscale",
                                        "Tailnet-only via tailscale serve; TLS is Tailscale's, no third-party edge.",
                                        WebServer.DEFAULT_HOST) {
            @Override
            List<String> command(int port) {
                // Foreground serve (no --bg): the mapping lives only as long as this child, so killing it on stop
                // tears the proxy down. The target is the loopback web server; only http://127.0.0.1 is valid there.
                return List.of("tailscale", "serve", String.valueOf(port));
            }

            @Override
            List<String> diagnose(String output) {
                if (output.contains("access denied"))
                    return List.of("   tailscale serve was denied permission to set the serve config (operator not set).",
                                                    "   Fix once, then retry:   sudo tailscale set --operator=$USER");
                if (output.contains("serve is not enabled"))
                    return List.of("   Serve is not enabled on your tailnet.",
                                                    "   Enable Serve + HTTPS certificates in the admin console: https://login.tailscale.com/admin");
                if (output.contains("failed to connect to local tailscaled"))
                    return tailscaledDown();
                return null;
            }
        };

        final String cliName; // the binary, also used in failure messages
        final Pattern urlPattern; // what the child prints as its public URL; null for NONE, which starts no child
        final String title; // what the banner calls this channel
        final String caveat; // banner security note; "" for NONE
        // Where the web server binds. A pure function of the channel: only NONE is exposed on the LAN, because for it
        // the bind IS the exposure; a tunnel fronts loopback. Passing it in meant a new constant could be wired to
        // 0.0.0.0 with nothing to catch it -- the same silence the child-spawning switch was made exhaustive to stop.
        final String bindHost;

        Tunnel(String cliName, String urlPattern, String title, String caveat, String bindHost) {
            this.cliName = cliName;
            this.urlPattern = urlPattern == null ? null : Pattern.compile(urlPattern);
            this.title = title;
            this.caveat = caveat;
            this.bindHost = bindHost;
        }

        /**
         * The child that publishes this channel, as a command line.
         *
         * <p>
         * Abstract, so the compiler asks for it where the constant is declared -- a stronger version of what the
         * exhaustive switch used to buy at the spawn site, since a constant cannot even be written without answering.
         * The commands lived in two static methods and a switch beside them; here each sits with the channel it belongs
         * to, and adding a channel is one constant, as the recipe in AGENTS.md says it should be.
         */
        abstract List<String> command(int port);

        /**
         * The fix lines for a failure signature this channel recognises in its child's output, or null for anything
         * else. Lives on the constant because the signatures are per-channel data: as a chain of {@code tunnel ==}
         * tests elsewhere, a new channel silently got no diagnosis and the caller fell back to "is the network
         * reachable?" for a cause it could have named.
         */
        List<String> diagnose(String output) {
            return null;
        }
    }

    private static final Duration TUNNEL_URL_WAIT = Duration.ofSeconds(20);

    /**
     * Start the web server on {@code host} and, for the tunnel variants, layer a foreground tunnel child in front of
     * it. One startup path for all three web modes; the tunnel only changes the banner and adds a process to tear down.
     */
    private List<String> startWeb(Tunnel tunnel) {
        if (active != null)
            return List.of("remote control already active. Stop it first");
        String token = WebServer.newPairingToken();
        int port = WebServer.DEFAULT_PORT;
        String host = tunnel.bindHost;
        var server = new WebServer(session, host, port, session.llm(), statusLines, commands.get(), token);
        try {
            server.start();
        } catch (Exception e) {
            return List.of("could not start web remote control: " + e.getMessage());
        }

        Process child = null;
        String label;
        List<String> banner;
        String title = tunnel.title;
        if (tunnel == Tunnel.NONE) {
            label = title + " :" + port;
            banner = new ArrayList<>();
            banner.add("   Anyone who can reach this host AND has the token can drive the agent (it runs shell + edits files).");
            banner.addAll(WebServer.accessBanner(host, port, token));
        } else {
            String publicUrl;
            var childOutput = new ArrayList<String>(); // collected while we wait, for diagnosing a failure
            try {
                // No drain thread yet: awaitTunnelUrl reads the stream itself, and two readers on one pipe deadlock
                // (the drain's transferTo holds the stream monitor while the URL reader blocks waiting for it).
                child = new ProcessBuilder(tunnel.command(port)).redirectErrorStream(true).start();
                publicUrl = awaitTunnelUrl(child, tunnel, childOutput);
                drainAsync(child.getInputStream()); // hand the pipe to the drain once the URL is captured
            } catch (Exception e) {
                if (child != null)
                    child.destroyForcibly();
                server.close();
                return List.of(tunnel.cliName + " tunnel failed to start: " + e.getMessage());
            }
            if (publicUrl == null) {
                child.destroyForcibly();
                server.close();
                return tunnelFailure(tunnel, childOutput);
            }
            label = title + " " + publicUrl;
            banner = new ArrayList<>();
            banner.add("   " + tunnel.caveat);
            banner.add("   Anyone in reach with this link can drive the agent (it runs shell + edits files).");
            banner.addAll(QrTerminal.scannable("scan to open on your phone:", publicUrl + "/?t=" + token));
        }

        var out = new ArrayList<String>();
        out.add("🔗 Remote control (" + title + ") is live on this session, token-gated.");
        out.addAll(banner);
        out.add("   /remote-control → re-show this link & QR, or stop.");
        Process c = child;
        activate(server.ui(), label, () -> {
            if (c != null)
                c.destroyForcibly(); // kill the foreground tunnel child so its mapping dies with pijota
            server.close();
        }, out);
        return out;
    }

    /**
     * Whether {@code cmd} runs and exits 0. Output goes to the void rather than to a pipe nobody reads: waiting on a
     * child whose pipe has filled deadlocks, and {@code tailscale status --json} prints the whole network map.
     */
    /** Runs a command purely for its exit status. Not a presence check -- {@link Executables#isAvailable} is. */
    private static boolean commandSucceeds(String... cmd) {
        try {
            return new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                            .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Drains the stream after the URL is captured, so a full pipe can't stall the long-lived child. */
    private static void drainAsync(InputStream in) {
        Thread.ofPlatform().daemon().name("pijota-tunnel-drain").start(() -> {
            try {
                in.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {}
        });
    }

    /**
     * Reads the tunnel child's output until its public URL appears or the wait expires. cloudflared prints a random
     * {@code trycloudflare.com} name; {@code tailscale serve} prints the deterministic {@code <node>.<tailnet>.ts.net}
     * address. Every line read is also appended to {@code sink} so a failure can be diagnosed from what the child
     * actually said. The caller owns the stream until this returns; only then does {@link #drainAsync} take over, so
     * the two never contend on the same pipe.
     */
    static String awaitTunnelUrl(Process tunnel, Tunnel kind, List<String> sink) throws InterruptedException {
        var pattern = kind.urlPattern;
        long deadline = System.nanoTime() + TUNNEL_URL_WAIT.toNanos();
        var reader = new BufferedReader(new InputStreamReader(tunnel.getInputStream(), StandardCharsets.UTF_8));
        try {
            String line;
            while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
                sink.add(line);
                var m = pattern.matcher(line);
                if (m.find())
                    return m.group();
            }
        } catch (IOException ignored) {}
        return null;
    }

    /**
     * The tunnel child started but no public URL appeared. Diagnose from its captured output: each known failure names
     * the cause and the exact fix, rather than the misleading "is the network reachable?". Unknown output falls back to
     * echoing what the child said plus a generic network hint.
     */
    static List<String> tunnelFailure(Tunnel tunnel, List<String> childOutput) {
        String all = String.join("\n", childOutput).toLowerCase(Locale.ROOT);
        var out = new ArrayList<String>();
        out.add(tunnel.cliName + " tunnel failed.");
        var known = tunnel.diagnose(all);
        if (known != null) {
            out.addAll(known);
            return out;
        }
        // Unknown: show what the child actually said (its real error) plus the generic reachability hint.
        if (!childOutput.isEmpty()) {
            out.add("   It said:");
            childOutput.stream().filter(l -> !l.isBlank()).limit(4).forEach(l -> out.add("     " + l.strip()));
        }
        out.add("   No public URL within " + TUNNEL_URL_WAIT.toSeconds() + "s -- is the network reachable?");
        return out;
    }

    /** How to bring tailscaled up and logged in; shared by the pre-flight check and the failure diagnosis. */
    private static List<String> tailscaledDown() {
        return List.of("   tailscaled isn't running or you're not logged in. Start it, then retry:",
                                        "     Linux: sudo systemctl enable --now tailscaled && sudo tailscale up",
                                        "     macOS/Windows: open the Tailscale app and sign in");
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
            // best effort, onStop detaches the session listener and stops the server/poll loop
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
