package org.pijota.tui;

import org.pijota.llm.ThinkingLevel;
import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptPresenterTest {

    private final TurnActivity activity = new TurnActivity();
    private final List<String> queuedFollowUps = new java.util.ArrayList<>();
    private final StatusOverride statusOverride = new StatusOverride();
    private ThinkingLevel level = ThinkingLevel.MEDIUM;
    private long tokens = 1000;
    private int messages = 3;
    private String sessionName;
    private List<String> extensionLines = List.of();

    /** The presenter wired to this test's collaborators, so a test can put the bar into a real state and read it. */
    private PromptPresenter presenter() {
        var theme = AnsiTheme.dark();
        var host = new PromptPresenter.Host() {
            @Override
            public AnsiTheme theme() {
                return theme;
            }

            @Override
            public String cwd() {
                return "/w";
            }

            @Override
            public String sessionName() {
                return sessionName;
            }

            @Override
            public String provider() {
                return "deepseek";
            }

            @Override
            public String model() {
                return "deepseek-v4-flash";
            }

            @Override
            public ThinkingLevel thinkingLevel() {
                return level;
            }

            @Override
            public long contextWindow() {
                return 1_000_000;
            }

            @Override
            public int messageCount() {
                return messages;
            }

            @Override
            public String liveStreamTail() {
                return null;
            }
        };
        return new PromptPresenter(host, activity, statusOverride, new DialogHandler(new DialogHandler.Host() {
            @Override
            public void clearStatus() {}
        }), new WorkingAnimation(), () -> List.copyOf(queuedFollowUps),
                                        new ContextTokenMeter(() -> messages, () -> tokens, 60_000),
                                        () -> extensionLines);
    }

    private PromptSnapshot decorate(String... promptLines) {
        var lines = promptLines.length == 0 ? List.of("") : List.of(promptLines);
        return presenter().decorate(new PromptSnapshot(lines, 0, 0, List.of()));
    }

    /** The status line as drawn, stripped of styling. */
    private String statusBar() {
        return AnsiStripper.strip(decorate().status());
    }

    private String statusBar(ThinkingLevel forLevel) {
        level = forLevel;
        return statusBar();
    }

    /**
     * Every level is named, {@code off} included. Hiding OFF made the chip vanish on the one Shift+Tab press that turns
     * thinking off, so a piece of the bar disappeared exactly where a setting should have appeared -- and since the
     * transient "thinking: x" line was retired in favour of "the chip is the feedback", that press had no feedback at
     * all. Themes give OFF a near-invisible tone for this: present and readable, saying nothing loudly.
     */
    @Test
    void theStatusBarNamesEveryEffortLevelIncludingOff() {
        for (var level : ThinkingLevel.values())
            assertTrue(statusBar(level).contains(" · " + level.label()),
                                            level + " is not named in the status bar: " + statusBar(level));
    }

    /** Cycling must never produce two presses that look the same, or Shift+Tab stops being legible. */
    @Test
    void noTwoLevelsRenderTheSameStatusBar() {
        var seen = new LinkedHashMap<String, ThinkingLevel>();
        for (var level : ThinkingLevel.values()) {
            var theme = AnsiTheme.dark();
            String styled = theme.styled(level.themeSlot(), level.label());
            var clash = seen.put(styled, level);
            assertNull(clash, level + " renders identically to " + clash);
        }
    }

    /** The model is named the way it is named everywhere else: provider/id, verbatim, pasteable back into --model. */
    @Test
    void theStatusBarNamesTheModelTheWayEverythingElseDoes() {
        assertTrue(statusBar(ThinkingLevel.MEDIUM).contains("deepseek/deepseek-v4-flash"),
                                        statusBar(ThinkingLevel.MEDIUM));
    }

    // ---- the token meter -----------------------------------------------------------------------------------------

    /** Before a turn there is no estimate; a made-up number would be worse than saying so. */
    @Test
    void anUnmeasuredContextSaysSoRatherThanClaimingZero() {
        tokens = 0;
        assertTrue(statusBar().startsWith("?/1M"), statusBar());
        assertEquals(0, decorate().contextPct());
    }

    /**
     * The used figure keeps both fractional digits. It is redrawn several times a second, and a trimmed one costs a
     * column at 4.30K and hands it back at 4.31K, so the line either side of it twitches while nothing has happened.
     * The window beside it needs no such treatment: it is fixed for the life of a model, so it spells itself the short
     * way.
     */
    @Test
    void theMeterShowsWhatIsUsedAgainstTheWindow() {
        tokens = 250_000;
        assertTrue(statusBar().startsWith("250.00K/1M (25%)"), statusBar());
        assertEquals(25, decorate().contextPct());

        tokens = 4300; // the case that used to lose a column: "4.3K" beside the "4.27K" of a moment earlier
        assertTrue(statusBar().startsWith("4.30K/"), statusBar());
    }

    /**
     * The percentage escalates in colour as the window fills: quiet, then amber, then the failure red. It is the only
     * warning before a compaction, so it has to change before it is urgent.
     */
    @Test
    void theMeterGetsLouderAsTheWindowFills() {
        var theme = AnsiTheme.dark();
        record Case(long tokens, String slot, String why) {
        }
        for (var c : List.of(new Case(500_000, "statusText", "half full is unremarkable"),
                                        new Case(750_000, "statusWarn", "three quarters is worth noticing"),
                                        new Case(950_000, "toolError", "nearly full is the last warning"))) {
            tokens = c.tokens();
            assertTrue(decorate().status().contains(theme.style(c.slot())), c.why() + ": " + statusBar());
        }
    }

    /** The bar never claims a full window: 99% is the ceiling, because 100% reads as "already too late". */
    @Test
    void theMeterNeverReadsAsCompletelyFull() {
        tokens = 10_000_000;
        assertTrue(statusBar().contains("(99%)"), statusBar());
        assertEquals(99, decorate().contextPct());
    }

    // ---- what a running turn adds --------------------------------------------------------------------------------

    @Test
    void anIdleBarCarriesNoActivity() {
        assertFalse(statusBar().contains("waiting"), statusBar());
        assertEquals(PromptSnapshot.PromptState.IDLE, decorate().promptState());
    }

    @Test
    void aRunningTurnNamesItsPhaseAndSaysTheBarIsBusy() {
        activity.begin("waiting for model");
        assertTrue(statusBar().contains("waiting for model"), statusBar());
        assertEquals(PromptSnapshot.PromptState.BUSY, decorate().promptState());
    }

    /** A sub-second turn is not timed: a counter that flashes 0s and vanishes is noise, not progress. */
    @Test
    void onlyATurnWorthWaitingForIsTimed() {
        activity.begin("waiting for model");
        assertFalse(statusBar().matches(".*\\b0s\\b.*"), "an instant turn shows no counter: " + statusBar());
    }

    // ---- queued follow-ups and the start hint ---------------------------------------------------------------------

    /** Typing while the model works queues the message; the bar has to say so, and how to get it back. */
    @Test
    void aQueuedFollowUpIsAnnouncedWithTheWayToRetrieveIt() {
        queuedFollowUps.add("and then run the tests");
        assertTrue(statusBar().contains("queued 1"), statusBar());
        assertTrue(statusBar().contains("/undo"), "and how to get it back: " + statusBar());
    }

    @Test
    void theStartHintAppearsOnlyOnAnEmptyConversation() {
        messages = 0;
        assertTrue(statusBar().contains("/help"), "a fresh session says where to start: " + statusBar());
        messages = 1;
        assertFalse(statusBar().contains("/help"), "and stops once there is a conversation: " + statusBar());
    }

    /** A queued message outranks the hint: it is news, and the hint is for someone who has not started. */
    @Test
    void aQueuedMessageTakesTheHintsPlaceRatherThanCrowdingIt() {
        messages = 0;
        queuedFollowUps.add("later");
        assertTrue(statusBar().contains("queued 1"), statusBar());
        assertFalse(statusBar().contains("/help"), "both at once would be two things competing: " + statusBar());
    }

    // ---- the workspace line and prompt state ----------------------------------------------------------------------

    @Test
    void theWorkspaceLineNamesTheDirectoryAndTheSessionWhenThereIsOne() {
        assertEquals("/w", AnsiStripper.strip(decorate().cwd()));
        sessionName = "refactor-tui";
        assertTrue(AnsiStripper.strip(decorate().cwd()).contains("refactor-tui"), decorate().cwd());
    }

    /**
     * An unclosed fence means Enter should add a line rather than send, so the caret says so. Without it, pasting a
     * code block and pressing Enter sent half of it.
     */
    @Test
    void anOpenCodeFenceMakesThePromptAContinuation() {
        assertEquals(PromptSnapshot.PromptState.CONTINUATION, decorate("```java", "int x = 1;").promptState());
        assertEquals(PromptSnapshot.PromptState.IDLE, decorate("```java", "int x = 1;", "```").promptState());
    }

    @Test
    void anErrorOverrideTurnsThePromptRed() {
        statusOverride.set("Error: boom");
        assertEquals(PromptSnapshot.PromptState.ERROR, decorate().promptState());
        statusOverride.set("Interrupted");
        assertEquals(PromptSnapshot.PromptState.ERROR, decorate().promptState());
        statusOverride.set("saved");
        assertEquals(PromptSnapshot.PromptState.IDLE, decorate().promptState(), "an ordinary notice is not an error");
    }

    /** Busy wins over an error override: what the bar is doing now outranks what went wrong before. */
    @Test
    void aRunningTurnOutranksAStaleError() {
        statusOverride.set("Error: boom");
        activity.begin("waiting for model");
        assertEquals(PromptSnapshot.PromptState.BUSY, decorate().promptState());
    }

    // ---- extension status lines ------------------------------------------------------------------------------------

    @Test
    void extensionLinesReachTheBarDedupedAndSingleRow() {
        extensionLines = List.of("⏸ plan", "⏸ plan", "one\ntwo", "  ");
        assertEquals(List.of("⏸ plan", "one two"), decorate().extensionLines(),
                                        "duplicates collapse, a newline would break the frame's row count, blanks go");
    }
}
