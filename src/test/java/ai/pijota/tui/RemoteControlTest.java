package ai.pijota.tui;

import ai.pijota.agent.TestSession;
import ai.pijota.config.AppConfig;
import ai.pijota.config.SettingsManager;
import ai.pijota.extensions.testkit.FakeUiContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
    }

    @Test
    void stopWhenInactiveIsANoop(@TempDir Path tmp) {
        var rc = remoteControl(tmp);
        assertTrue(String.join(" ", rc.stop()).contains("no remote control active"));
        assertFalse(rc.active());
    }

    @Test
    void telegramReportsMissingEnvWhenNotConfigured(@TempDir Path tmp) {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("TELEGRAM_BOT_TOKEN") == null,
                                        "skip when a bot token is configured in the environment");
        var rc = remoteControl(tmp);
        assertTrue(String.join(" ", rc.startTelegram()).contains("TELEGRAM_BOT_TOKEN"));
        assertFalse(rc.active(), "a failed start leaves no active channel");
    }
}
