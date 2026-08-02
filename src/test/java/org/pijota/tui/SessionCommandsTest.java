package org.pijota.tui;

import org.pijota.agent.PijotaSession;
import org.pijota.agent.TestSession;
import org.pijota.config.AppConfig;
import org.pijota.config.SettingsManager;
import org.pijota.llm.ThinkingLevel;

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
        var lines = SessionCommands.usageReport(1500, 500, 0, 0, 0, 0.0123, 5.0, "  rate    unknown",
                                        (slot, text) -> text);
        String out = String.join("\n", lines);
        assertTrue(out.contains("input"), out);
        assertTrue(out.contains("total"), out);
        assertTrue(out.contains("~$0.01"), "cost is rounded to cents, not shown to four decimals: " + out);
        assertTrue(out.contains("budget") && out.contains("$5.00"), out);
        assertFalse(out.contains("cache"), "no cache line when no cache tokens"); // cacheRead/write are 0 here
        assertFalse(out.contains("reasoning"), "no reasoning annotation when reasoning is 0");
    }

    @Test
    void costIsWrittenTheWayAPersonWouldSayIt() {
        // It used to read "~$0.0000 est" before a single token was spent: four decimals of zero, and two hedges
        // ("~" and "est") for one uncertainty.
        assertEquals("none yet", SessionCommands.formatSessionCost(0));
        assertEquals("none yet", SessionCommands.formatSessionCost(-1));
        assertEquals("under $0.01", SessionCommands.formatSessionCost(0.004));
        assertEquals("~$0.01", SessionCommands.formatSessionCost(0.0123));
        assertEquals("~$1.50", SessionCommands.formatSessionCost(1.5));
    }

    @Test
    void aFreshSessionReportsNoSpendRatherThanAStringOfZeroes() {
        var lines = SessionCommands.usageReport(0, 0, 0, 0, 0, 0.0, null, "unknown", (slot, text) -> text);
        String out = String.join("\n", lines);
        assertTrue(out.contains("none yet"), out);
        assertFalse(out.contains("0.0000"), "no four-decimal zero: " + out);
    }

    @Test
    void usageReportOmitsBudgetWhenUnset() {
        var lines = SessionCommands.usageReport(10, 20, 0, 0, 0, 0.0, null, "  rate    unknown", (slot, text) -> text);
        assertFalse(String.join("\n", lines).contains("budget"));
    }

    @Test
    void usageReportIncludesRateLineWhenProvided() {
        var lines = SessionCommands.usageReport(10, 20, 0, 0, 0, 0.0, null,
                                        "  rate    $5.00 in / $25.00 out per 1M  (x)", (slot, text) -> text);
        assertTrue(String.join("\n", lines).contains("$5.00 in / $25.00 out"));
    }

    @Test
    void usageReportShowsCacheHitRateWhenCached() {
        // 4000 read of a 5000-token prompt (200 fresh input + 4000 read + 800 write) → 80% hit.
        var lines = SessionCommands.usageReport(200, 100, 0, 4000, 800, 0.0, null, "  rate", (slot, text) -> text);
        String out = String.join("\n", lines);
        assertTrue(out.contains("cache") && out.contains("read") && out.contains("write"), out);
        assertTrue(out.contains("80% hit"), out);
    }

    @Test
    void usageReportAnnotatesOutputWithReasoningTokens() {
        var lines = SessionCommands.usageReport(100, 900, 600, 0, 0, 0.0, null, "  rate", (slot, text) -> text);
        String out = String.join("\n", lines);
        assertTrue(out.contains("output") && out.contains("reasoning"), out); // 600 of the 900 output are reasoning
    }

    @Test
    void rateLineShowsCatalogRatesForKnownModel() {
        String line = SessionCommands.rateLine("anthropic", "claude-opus-4-5-20251101", false);
        assertTrue(line.contains("$5.00 in / $25.00 out per MTok"), line); // the unit the model picker prints too
        assertTrue(line.contains("anthropic/claude-opus-4-5-20251101"), line);
    }

    /** One rendering of a model name, everywhere. The provider is a prefix, not something to say twice. */
    @Test
    void everyModelLabelInUsageIsTheCompactForm() {
        assertEquals("deepseek/v4-flash", SessionCommands.breakdownLabel("deepseek/deepseek-v4-flash"));
        assertTrue(SessionCommands.rateLine("deepseek", "deepseek-v4-flash", false).contains("deepseek/v4-flash"));
        assertEquals("Tools/summaries", SessionCommands.breakdownLabel("Tools/summaries"),
                                        "the compaction row is not a model and must survive untouched");
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
    void exportWithFormatTokenAndPathWritesToThatPath() {
        var host = hostWith(true);
        Path out = tempDir.resolve("exp.md");

        // "/export md <path>": the leading format token must be parsed off, not folded into the filename.
        new SessionCommands(host).exportSession("/export md " + out);

        assertTrue(Files.exists(out), host.lines.toString());
        assertTrue(String.join(" ", host.lines).contains("exported to"), host.lines.toString());
    }

    @Test
    void exportWithFormatOnlyUsesDefaultFilename() {
        var host = hostWith(true);

        new SessionCommands(host).exportSession("/export md"); // format, no path → default name, not a file named "md"

        assertFalse(Files.exists(Path.of(host.session().cwd()).resolve("md")), "must not name the file 'md'");
        assertTrue(String.join(" ", host.lines).contains("exported to"), host.lines.toString());
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

    /**
     * Red in this interface means something failed. A command declining to start while a turn is running has failed at
     * nothing, and nine call sites spent the failure colour on it -- in two different phrasings of the same sentence.
     */
    @Test
    void refusingACommandMidTurnIsNotAFailure() {
        var said = new ArrayList<String>();
        var slots = new ArrayList<String>();
        class Busy implements TuiHost {
            boolean busy = true;

            @Override
            public boolean busy() {
                return busy;
            }

            @Override
            public String styled(String slot, String text) {
                slots.add(slot);
                return text;
            }

            @Override
            public void appendPlain(List<String> lines) {
                said.addAll(lines);
            }
        }
        var host = new Busy();

        assertTrue(host.refuseWhileBusy("/model"), "it reports the refusal so callers can just return");
        assertEquals(List.of("/model is unavailable while a turn is running"), said);
        assertEquals(List.of("statusWarn"), slots, "amber, not the red that means something broke");

        said.clear();
        host.busy = false;
        assertFalse(host.refuseWhileBusy("/model"), "idle: nothing is refused");
        assertTrue(said.isEmpty(), "and nothing is said");
    }

    /** One sentence for one condition; "/compact unavailable while model is responding" was a second one. */
    @Test
    void everyBusyRefusalReadsTheSameWay() {
        assertEquals("/compact is unavailable while a turn is running", TuiHost.busyRefusal("/compact"));
        assertEquals("command palette is unavailable while a turn is running", TuiHost.busyRefusal("command palette"));
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
