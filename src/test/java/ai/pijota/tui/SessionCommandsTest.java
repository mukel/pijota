package ai.pijota.tui;

import ai.pijota.agent.PijotaSession;
import ai.pijota.config.AppConfig;
import ai.pijota.config.SettingsManager;
import ai.pijota.llm.ThinkingLevel;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionCommandsTest {

    @Test
    void startNewSessionRefusedWhenBusy() {
        var host = new FakeHost();
        host.busy = true;
        var sc = new SessionCommands(host);

        sc.startNewSession();

        assertFalse(host.lines.isEmpty());
        assertTrue(host.lines.get(0).contains("unavailable"));
    }

    @Test
    void cloneSessionRefusedWhenBusy() {
        var host = new FakeHost();
        host.busy = true;
        var sc = new SessionCommands(host);

        sc.cloneSession();

        assertTrue(host.lines.get(0).contains("unavailable"));
    }

    @Test
    void deleteSessionShowsErrorWhenBlank() {
        var host = new FakeHost();
        var sc = new SessionCommands(host);

        sc.deleteSession("/delete ");

        assertTrue(host.lines.get(0).contains("usage"));
    }

    @Test
    void deleteSessionShowsErrorWhenNoSpec() {
        var host = new FakeHost();
        var sc = new SessionCommands(host);

        sc.deleteSession("/delete");

        assertTrue(host.lines.get(0).contains("usage"));
    }

    @Test
    void showSessionInfoIncludesProviderAndModel() {
        var host = new FakeHost();
        var sc = new SessionCommands(host);

        sc.showSessionInfo();

        String joined = String.join(" ", host.lines);
        assertTrue(joined.contains("openai"));
        assertTrue(joined.contains("gpt-4"));
        assertTrue(joined.contains("thinking off"));
    }

    @Test
    void showSessionInfoIncludesSessionNameWhenSet() {
        var host = new FakeHost();
        host.sessionName = "my-session";
        var sc = new SessionCommands(host);

        sc.showSessionInfo();

        String joined = String.join(" ", host.lines);
        assertTrue(joined.contains("my-session"));
    }

    @Test
    void showSessionInfoOmitsNameWhenNull() {
        var host = new FakeHost();
        host.sessionName = null;
        var sc = new SessionCommands(host);

        sc.showSessionInfo();

        String joined = String.join(" ", host.lines);
        assertFalse(joined.contains("name"));
    }

    private static final class FakeHost implements SessionCommands.Host {
        boolean busy;
        String sessionName = "test-session";
        final List<String> lines = new ArrayList<>();

        @Override
        public PijotaSession session() {
            return null;
        }

        @Override
        public String styled(String slot, String text) {
            return text;
        }

        @Override
        public void appendPlain(List<String> lines) {
            this.lines.addAll(lines);
        }

        @Override
        public boolean busy() {
            return busy;
        }

        @Override
        public String sessionName() {
            return sessionName;
        }

        @Override
        public void setSessionName(String name) {
            this.sessionName = name;
        }

        @Override
        public String provider() {
            return "openai";
        }

        @Override
        public String model() {
            return "gpt-4";
        }

        @Override
        public String cwd() {
            return "/tmp";
        }

        @Override
        public ThinkingLevel thinkingLevel() {
            return ThinkingLevel.OFF;
        }

        @Override
        public SettingsManager settings() {
            return new SettingsManager(new AppConfig(), c -> {});
        }

        @Override
        public String tokenStatus() {
            return "0/10K";
        }

        @Override
        public void refreshSessionName() {}
    }
}
