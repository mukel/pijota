package org.pijota.tui;

import static org.junit.jupiter.api.Assertions.*;

import org.pijota.llm.ThinkingLevel;
import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;

import java.util.List;

import org.junit.jupiter.api.Test;

class PromptPresenterTest {

    /** The status bar as it would be drawn, for a given effort level and nothing else going on. */
    private static String statusBar(ThinkingLevel level) {
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
                return null;
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
                return 3;
            }

            @Override
            public String liveStreamTail() {
                return null;
            }
        };
        var presenter = new PromptPresenter(host, new TurnActivity(), new StatusOverride(),
                                        new DialogHandler(new DialogHandler.Host() {
                                            @Override
                                            public void setStatus(String status) {}

                                            @Override
                                            public void clearStatus() {}
                                        }), new WorkingAnimation(), new FollowUpQueue(),
                                        new ContextTokenMeter(() -> 3, () -> 1000, 1000), List::of);
        return AnsiStripper.strip(presenter.decorate(new PromptSnapshot(List.of(""), 0, 0, List.of())).status());
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
        var seen = new java.util.LinkedHashMap<String, ThinkingLevel>();
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
}
