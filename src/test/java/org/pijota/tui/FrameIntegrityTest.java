package org.pijota.tui;

import org.pijota.theme.AnsiTheme;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The renderer's contract, made executable: whatever the prompt frame does — typing, wide glyphs, streaming, long
 * warnings, modal regions, width changes, frames taller than the screen — committed scrollback lines must appear
 * exactly once, in order, forever. One misdrawn frame after an external event is tolerable; drift that eats or
 * duplicates a line on every redraw is the bug class this suite exists to keep extinct.
 */
class FrameIntegrityTest {

    private static final AnsiTheme THEME = AnsiTheme.dark();

    private static PromptLayout productionLayout() {
        return new PromptLayout(List.of(new PromptRegion.Editor(), new AppendOnlyTui.PendingRegion(),
                                        new PromptRegion.Completions(), new PromptRegion.StatusBar()));
    }

    private static PromptSnapshot snap(String line, int cursorCol) {
        return new PromptSnapshot(List.of(line), 0, cursorCol, List.of()).withCwd("~/workspace (main)")
                                        .withStatus("12.3k/200k (6%)  (anthropic) claude");
    }

    private static String sentinel(int n) {
        return "<sentinel-%03d>".formatted(n);
    }

    /** Asserts every sentinel committed so far appears exactly once, in order, across scrollback + screen. */
    private static void assertIntact(VtEmulator term, int committedCount) {
        var all = new ArrayList<>(term.scrollback());
        all.addAll(term.screenLines());
        int prev = -1;
        for (int n = 0; n < committedCount; n++) {
            String s = sentinel(n);
            int found = -1;
            int count = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).contains(s)) {
                    count++;
                    found = i;
                }
            }
            assertEquals(1, count, s + " must appear exactly once (eaten if 0, duplicated if >1):\n"
                                            + String.join("\n", all));
            assertTrue(found > prev, s + " out of order:\n" + String.join("\n", all));
            prev = found;
        }
    }

    @Test
    void typingWideGlyphsWhileCommittingNeverEatsOrDuplicatesScrollback() {
        int width = 40;
        int height = 12;
        var term = new VtEmulator(width, height);
        var renderer = new TerminalFrameRenderer(productionLayout());

        String text = "type 漢字漢字 and emoji 🚀🚀 across the wrap boundary and beyond it";
        int committed = 0;
        int frame = 0;
        for (int i = 0; i <= text.length(); i = i < text.length() ? text.offsetByCodePoints(i, 1) : i + 1) {
            String typed = text.substring(0, Math.min(i, text.length()));
            var lines = frame++ % 5 == 0
                                            ? List.of(StyledLine.of(sentinel(committed++) + " output line"))
                                            : List.<StyledLine>of();
            term.feed(renderer.renderFrame(width, height, snap(typed, typed.length()), lines, true, true, THEME));
            assertIntact(term, committed);
        }
    }

    @Test
    void streamingWithLongWarningAndQueuedMessagesNeverDrifts() {
        int width = 40;
        int height = 12;
        var term = new VtEmulator(width, height);
        var renderer = new TerminalFrameRenderer(productionLayout());

        int committed = 0;
        var tail = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            tail.append("token-").append(i).append(' ');
            var snapshot = snap("follow-up", 9).withStreamTail(tail.toString())
                                            .withWarning("Error: " + "x".repeat(120) + "\nsecond warning line")
                                            .withPendingMessages(List.of("a queued follow-up message far longer than "
                                                                            + "the terminal is wide so it must clamp"));
            var lines = i % 4 == 0
                                            ? List.of(StyledLine.of(sentinel(committed++) + " streamed"))
                                            : List.<StyledLine>of();
            term.feed(renderer.renderFrame(width, height, snapshot, lines, true, true, THEME));
            assertIntact(term, committed);
        }
    }

    @Test
    void redrawingTheSameSnapshotIsIdempotent() {
        int width = 50;
        int height = 10;
        var term = new VtEmulator(width, height);
        var renderer = new TerminalFrameRenderer(productionLayout());

        var snapshot = snap("hello 漢字 world", 5);
        term.feed(renderer.renderFrame(width, height, snapshot, List.of(StyledLine.of("committed once")), true, true,
                                        THEME));
        var before = List.of(term.scrollback(), term.screenLines());
        for (int i = 0; i < 5; i++) {
            term.feed(renderer.renderFrame(width, height, snapshot, List.of(), true, true, THEME));
            assertEquals(before, List.of(term.scrollback(), term.screenLines()),
                                            "an unchanged snapshot must redraw pixel-identically — any delta compounds "
                                                                            + "into drift on every keystroke");
        }
    }

    @Test
    void frameTallerThanTheScreenStaysAnchoredAcrossRedraws() {
        int width = 40;
        int height = 8;
        var term = new VtEmulator(width, height);
        var renderer = new TerminalFrameRenderer(productionLayout());

        var paste = new ArrayList<String>();
        for (int i = 0; i < 30; i++)
            paste.add("pasted line " + i);
        var snapshot = new PromptSnapshot(paste, 29, 0, List.of()).withCwd("~/ws").withStatus("busy");

        int committed = 0;
        for (int i = 0; i < 10; i++) {
            var lines = i % 3 == 0
                                            ? List.of(StyledLine.of(sentinel(committed++) + " while tall"))
                                            : List.<StyledLine>of();
            term.feed(renderer.renderFrame(width, height, snapshot, lines, true, true, THEME));
            assertIntact(term, committed);
        }
    }

    @Test
    void widthChangeReanchorsWithoutTouchingScrollback() {
        int cols = 100;
        int height = 12;
        var term = new VtEmulator(cols, height);
        var renderer = new TerminalFrameRenderer(productionLayout());

        int committed = 0;
        for (int width : new int[] {80, 80, 60, 60, 90, 40, 40}) {
            term.feed(renderer.renderFrame(width, height, snap("resizing input", 5),
                                            List.of(StyledLine.of(sentinel(committed++) + " across widths")), true,
                                            true, THEME));
            // The emulator does not reflow (like most terminals when only the app's width belief changes), so even the
            // first frame after a width change must leave scrollback intact.
            assertIntact(term, committed);
        }
    }

    @Test
    void completionsAndModalRegionsComeAndGoWithoutShiftingHistory() {
        int width = 44;
        int height = 14;
        var term = new VtEmulator(width, height);
        var renderer = new TerminalFrameRenderer(productionLayout());

        var items = List.of(new CompletionItem("/model", "switch model"),
                                        new CompletionItem("/sessions", "manage sessions"),
                                        new CompletionItem("/help-with-a-very-long-name", "and a long description "
                                                                        + "that overflows the terminal width"));
        int committed = 0;
        for (int i = 0; i < 12; i++) {
            var snapshot = i % 2 == 0
                                            ? new PromptSnapshot(List.of("/m"), 0, 2, items, i % items.size())
                                                                            .withCwd("~/ws").withStatus("idle")
                                            : snap("/m", 2);
            var lines = List.of(StyledLine.of(sentinel(committed++) + " between toggles"));
            term.feed(renderer.renderFrame(width, height, snapshot, lines, true, true, THEME));
            assertIntact(term, committed);
        }
    }
}
