package ai.pijota.tui;

import ai.pijota.agent.PijotaSession;
import ai.pijota.config.AppConfig;
import ai.pijota.config.SettingsManager;
import ai.pijota.llm.ThinkingLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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

    @Test
    void deleteSessionAsksConfirmationAndCancels() {
        var host = new FakeHost();
        host.confirmAnswer = false;
        var sc = new SessionCommands(host);

        sc.deleteSession("/delete abc123");

        assertTrue(host.confirmQuestion.contains("abc123"), host.confirmQuestion);
        assertTrue(String.join(" ", host.lines).contains("cancelled"));
    }

    @Test
    void usageReportShowsTokensCostAndBudget() {
        var lines = SessionCommands.usageReport(1500, 500, 0.0123, 5.0, (slot, text) -> text);
        String out = String.join("\n", lines);
        assertTrue(out.contains("input"), out);
        assertTrue(out.contains("total"), out);
        assertTrue(out.contains("$0.0123"), out);
        assertTrue(out.contains("budget") && out.contains("$5.00"), out);
    }

    @Test
    void usageReportOmitsBudgetWhenUnset() {
        var lines = SessionCommands.usageReport(10, 20, 0.0, null, (slot, text) -> text);
        assertFalse(String.join("\n", lines).contains("budget"));
    }

    private static final class FakeHost implements SessionCommands.Host {
        boolean busy;
        String sessionName = "test-session";
        String confirmQuestion;
        boolean confirmAnswer = true;
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

        @Override
        public CompletableFuture<Boolean> confirm(String question) {
            this.confirmQuestion = question;
            return CompletableFuture.completedFuture(confirmAnswer);
        }
    }
}
