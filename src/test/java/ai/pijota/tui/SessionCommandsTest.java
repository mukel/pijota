package ai.pijota.tui;

import ai.pijota.agent.PijotaSession;
import ai.pijota.agent.TestSession;
import ai.pijota.config.AppConfig;
import ai.pijota.config.SettingsManager;
import ai.pijota.llm.ThinkingLevel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class SessionCommandsTest {

    @TempDir
    Path tempDir;

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

    @Test
    void usageReportIncludesRateLineWhenProvided() {
        var lines = SessionCommands.usageReport(10, 20, 0.0, null, "  rate    $5.00 in / $25.00 out per 1M  (x)",
                                        (slot, text) -> text);
        assertTrue(String.join("\n", lines).contains("$5.00 in / $25.00 out"));
    }

    @Test
    void rateLineShowsCatalogRatesForKnownModel() {
        String line = SessionCommands.rateLine("anthropic", "claude-opus-4-5-20251101", false);
        assertTrue(line.contains("$5.00 in / $25.00 out per 1M"), line);
        assertTrue(line.contains("claude-opus-4-5-20251101"), line);
    }

    @Test
    void rateLineReportsUnknownForUnpricedModel() {
        String line = SessionCommands.rateLine("openai-codex", "gpt-5.4", false);
        assertTrue(line.contains("unknown for gpt-5.4"), line);
    }

    // ---- session()-backed commands, driven through a real PijotaSession ----

    private FakeHost hostWith(boolean persisted) {
        var host = new FakeHost();
        host.session = persisted
                                        ? TestSession.in(tempDir).build().session()
                                        : TestSession.in(tempDir).noSession().build().session();
        return host;
    }

    @Test
    void startNewSessionWithEmptyTranscriptCreatesImmediately() {
        var host = hostWith(true);

        new SessionCommands(host).startNewSession();

        assertTrue(String.join(" ", host.lines).contains("started new session"), host.lines.toString());
    }

    @Test
    void saveSessionWritesToTheGivenPath() {
        var host = hostWith(true);
        Path out = tempDir.resolve("saved.jsonl");

        new SessionCommands(host).saveSession("/save " + out);

        assertTrue(Files.exists(out));
        assertTrue(String.join(" ", host.lines).contains("session saved to"), host.lines.toString());
    }

    @Test
    void showUsageReportsCumulativeTokens() {
        var host = hostWith(true);

        new SessionCommands(host).showUsage();

        assertTrue(String.join("\n", host.lines).contains("usage"), host.lines.toString());
    }

    @Test
    void showContextSummarizesTheTranscript() {
        var host = hostWith(true);

        new SessionCommands(host).showContext();

        assertTrue(String.join(" ", host.lines).contains("context"), host.lines.toString());
    }

    @Test
    void copyLastResponseReportsWhenNothingToCopy() {
        var host = hostWith(true);

        new SessionCommands(host).copyLastResponse();

        assertTrue(String.join(" ", host.lines).contains("no assistant response"), host.lines.toString());
    }

    @Test
    void cloneSessionWithoutAnActiveFileReportsNoSession() {
        var host = hostWith(false); // --no-session => sessionFile() is null

        new SessionCommands(host).cloneSession();

        assertTrue(String.join(" ", host.lines).contains("no active session"), host.lines.toString());
    }

    private static final class FakeHost implements SessionCommands.Host {
        boolean busy;
        String sessionName = "test-session";
        String confirmQuestion;
        boolean confirmAnswer = true;
        PijotaSession session; // null unless a test wires a real session
        final List<String> lines = new ArrayList<>();

        @Override
        public PijotaSession session() {
            return session;
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
