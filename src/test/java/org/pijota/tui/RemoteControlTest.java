package org.pijota.tui;

import org.pijota.agent.TestSession;
import org.pijota.config.AppConfig;
import org.pijota.config.SettingsManager;
import org.pijota.extensions.testkit.FakeUiContext;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RemoteControlTest {

    private static RemoteControl remoteControl(Path tmp) {
        var ts = TestSession.in(tmp).build();
        var settings = new SettingsManager(new AppConfig(), c -> {});
        return new RemoteControl(ts.session(), settings, List::of, List::of, new FakeUiContext());
    }

    @Test
    void inactiveByDefault(@TempDir Path tmp) {
        var rc = remoteControl(tmp);
        assertFalse(rc.active());
        assertNull(rc.summary());
        assertTrue(rc.info().isEmpty(), "no connection banner to re-show when inactive");
    }

    @Test
    void stopWhenInactiveIsANoop(@TempDir Path tmp) {
        var rc = remoteControl(tmp);
        assertTrue(String.join(" ", rc.stop()).contains("no remote control active"));
        assertFalse(rc.active());
    }

    @Test
    void telegramReportsMissingEnvWhenNotConfigured(@TempDir Path tmp) {
        Assumptions.assumeTrue(System.getenv("TELEGRAM_BOT_TOKEN") == null,
                                        "skip when a bot token is configured in the environment");
        var rc = remoteControl(tmp);
        assertTrue(String.join(" ", rc.startTelegram()).contains("TELEGRAM_BOT_TOKEN"));
        assertFalse(rc.active(), "a failed start leaves no active channel");
    }

    @Test
    void tunnelWithoutCloudflaredFailsGracefully(@TempDir Path tmp) {
        Assumptions.assumeTrue(!cloudflaredOnPath(), "skip when cloudflared is installed");
        var rc = remoteControl(tmp);
        assertTrue(String.join(" ", rc.startWebTunnel()).contains("cloudflared"));
        assertFalse(rc.active(), "a failed start leaves no active channel");
    }

    @Test
    void tunnelUrlRegexMatchesOnlyTrycloudflareHosts() {
        var p = RemoteControl.Tunnel.CLOUDFLARE.urlPattern;
        var m = p.matcher("INF +--...  https://word-word-word.trycloudflare.com  ...+");
        assertTrue(m.find());
        assertEquals("https://word-word-word.trycloudflare.com", m.group());
        assertFalse(p.matcher("https://evil-trycloudflare.com.evil.test").find(),
                                        "a lookalike host must not parse as the tunnel URL");
    }

    @Test
    void missingToolNamesTheToolAndInstallInstructions() {
        var msg = String.join("\n", RemoteControl.missingTool("cloudflared", "https://example.com",
                                        "Debian/Ubuntu: sudo apt install cloudflared",
                                        "macOS: brew install cloudflared"));
        assertTrue(msg.contains("cloudflared"));
        assertTrue(msg.contains("apt install"), "install instructions must be actionable per-OS");
        assertTrue(msg.contains("brew install"));
        assertTrue(msg.contains("https://example.com"));
    }

    @Test
    void cloudflaredRateLimitIsDiagnosedNotBlamedOnTheNetwork() {
        // cloudflared exits on HTTP 429 (error 1015) without retrying; the fix is to wait, not check the network.
        var msg = failure(RemoteControl.Tunnel.CLOUDFLARE, "ERR failed to sufficiently increase receive buffer",
                                        "ERR Request failed error=1015");
        assertTrue(msg.contains("rate-limited") || msg.contains("1015"), msg);
        assertTrue(msg.toLowerCase().contains("wait") || msg.toLowerCase().contains("within an hour"), msg);
        assertFalse(msg.contains("is the network reachable"), "a rate limit must not read as a network problem");
    }

    @Test
    void tailscaleOperatorDeniedPointsAtTheFix() {
        assertTrue(failure(RemoteControl.Tunnel.TAILSCALE, "sending serve config: Access denied: serve config denied")
                                        .contains("tailscale set --operator"));
    }

    @Test
    void tailscaleServeDisabledPointsAtTheAdminConsole() {
        var msg = failure(RemoteControl.Tunnel.TAILSCALE,
                                        "Serve is not enabled on your tailnet. To enable, visit: https://login.tailscale.com/f/serve");
        assertTrue(msg.contains("admin console") || msg.contains("login.tailscale.com"), msg);
    }

    @Test
    void unknownTunnelFailureEchoesTheChildsOwnError() {
        var msg = failure(RemoteControl.Tunnel.CLOUDFLARE, "", "ERR something entirely unexpected happened");
        assertTrue(msg.contains("something entirely unexpected"),
                                        "unknown failures must surface the child's real error");
        assertTrue(msg.contains("is the network reachable"), "and still keep the generic reachability hint");
    }

    /** Renders the tunnel-failure message for the given child output lines. */
    private static String failure(RemoteControl.Tunnel tunnel, String... childOutput) {
        return String.join("\n", RemoteControl.tunnelFailure(tunnel, List.of(childOutput)));
    }

    @Test
    void awaitTunnelUrlCapturesOutputAndFindsTheUrl() throws Exception {
        // A real (tiny) process stands in for cloudflared: it prints the banner line with the URL. The wait must both
        // extract the URL AND collect every line into the sink for later diagnosis.
        var fake = new ProcessBuilder("sh", "-c", "echo 'INF +---- https://word-word-word.trycloudflare.com ----+'")
                                        .start();
        var sink = new ArrayList<String>();
        String url = RemoteControl.awaitTunnelUrl(fake, RemoteControl.Tunnel.CLOUDFLARE, sink);
        assertEquals("https://word-word-word.trycloudflare.com", url);
        assertEquals(1, sink.size(), "the banner line must be captured for diagnosis");
        fake.destroyForcibly();
    }

    @Test
    void awaitTunnelUrlReturnsNullAndCollectsOutputWhenNoUrlAppears() throws Exception {
        // A child that only prints an error (the tailscale serve-denied shape) yields no URL but its lines survive.
        var fake = new ProcessBuilder("sh", "-c", "echo 'sending serve config: Access denied: serve config denied'")
                                        .start();
        var sink = new ArrayList<String>();
        String url = RemoteControl.awaitTunnelUrl(fake, RemoteControl.Tunnel.TAILSCALE, sink);
        assertNull(url, "no .ts.net URL printed -> no tunnel");
        var diagnosed = String.join("\n", RemoteControl.tunnelFailure(RemoteControl.Tunnel.TAILSCALE, sink));
        assertTrue(diagnosed.contains("tailscale set --operator"),
                                        "captured output must feed the diagnosis: " + diagnosed);
        fake.destroyForcibly();
    }

    private static boolean cloudflaredOnPath() {
        try {
            return new ProcessBuilder("cloudflared", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void startWebActivatesWithAReshowableBannerThenStopRestores(@TempDir Path tmp) {
        var rc = remoteControl(tmp);
        var banner = rc.startWeb();
        if (!rc.active()) { // the default port is busy on this host → graceful error, nothing else to assert
            assertTrue(String.join(" ", banner).toLowerCase().contains("could not start"), banner.toString());
            return;
        }
        try {
            assertTrue(rc.summary().startsWith("web :"), rc.summary());
            assertEquals(banner, rc.info(), "info() re-shows exactly the start banner");
            assertTrue(String.join("\n", rc.info()).contains("http://"), "banner carries the reachable URL");
            assertTrue(String.join(" ", rc.startWeb()).contains("already active"), "a second start is rejected");
        } finally {
            rc.stop();
        }
        assertFalse(rc.active(), "stop deactivates");
        assertNull(rc.summary());
        assertTrue(rc.info().isEmpty(), "nothing left to re-show after stop");
    }
}
