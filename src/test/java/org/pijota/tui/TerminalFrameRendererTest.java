package org.pijota.tui;

import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerminalFrameRendererTest {
    @Test
    void rendersAppendOnlyFrameWithoutStartingWriterThread() {
        var renderer = new TerminalFrameRenderer(new PromptLayout(List.of()));

        var frame = renderer.renderFrame(80, 40, null, List.of(StyledLine.of("assistant output")), true, false,
                                        AnsiTheme.dark());

        assertTrue(frame.startsWith(TerminalFrameRenderer.BEGIN_SYNC));
        assertTrue(frame.contains(TerminalFrameRenderer.HIDE_CURSOR));
        assertTrue(AnsiStripper.strip(frame).contains("assistant output"));
        assertTrue(frame.contains("\033[K\r\n"), "committed lines are written then trimmed (write-then-trim)");
        assertTrue(frame.endsWith(TerminalFrameRenderer.END_SYNC));
    }

    @Test
    void canRenderPromptFrameThroughPureRenderer() {
        var renderer = new TerminalFrameRenderer(new PromptLayout(List.of()));
        var snapshot = new PromptSnapshot(List.of("hello"), 0, 5, List.of());

        var frame = renderer.renderFrame(40, 40, snapshot, List.of(), true, true, AnsiTheme.dark());

        assertTrue(AnsiStripper.strip(frame).contains("─".repeat(10)));
        assertTrue(frame.contains(TerminalFrameRenderer.SHOW_CURSOR));
    }
}
