package org.pijota.tui;

import org.pijota.testkit.AnsiStripper;
import org.pijota.theme.AnsiTheme;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The coordinator is the single point every scrollback line passes through, and it owns two things nothing else can:
 * the spacing policy (never two consecutive blank lines, so blocks may each claim their own line of air without
 * coordinating) and the writer thread that coalesces a batch of ops into one frame. Both were untested -- the spacing
 * rule in particular is relied on by StreamRenderer, which deliberately emits a blank it expects to be collapsed.
 */
class TerminalCoordinatorTest {

    /** A terminal whose output we can read back. Piped, not a byte array: JLine closes a stream that hits EOF. */
    private record Rig(TerminalCoordinator coordinator, Terminal terminal, ByteArrayOutputStream sink,
                                    PipedOutputStream toTerminal) implements AutoCloseable {
        String rendered() {
            terminal.writer().flush();
            return AnsiStripper.strip(sink.toString(StandardCharsets.UTF_8));
        }

        /**
         * Rendered rows, in order. The carriage returns the frame protocol writes before each row have to go first:
         * String.lines() treats a lone \r as a line break, which would invent a blank row between every real one.
         */
        List<String> scrollback() {
            return rendered().replace("\r", "").lines().toList();
        }

        @Override
        public void close() throws Exception {
            coordinator.close();
            toTerminal.close();
            terminal.close();
        }
    }

    private static Rig rig() throws Exception {
        var sink = new ByteArrayOutputStream();
        var toTerminal = new PipedOutputStream();
        var in = new PipedInputStream(toTerminal, 1024);
        Terminal terminal = TerminalBuilder.builder().streams(in, sink).build();
        var theme = AnsiTheme.dark();
        var coordinator = new TerminalCoordinator(terminal, theme, new PromptLayout(List.of()));
        return new Rig(coordinator, terminal, sink, toTerminal);
    }

    private static List<StyledLine> lines(String... texts) {
        var out = new ArrayList<StyledLine>();
        for (String t : texts)
            out.add(StyledLine.of(t));
        return out;
    }

    /** Waits for the writer thread to drain rather than sleeping a fixed amount. */
    private static void settle(Rig rig, String marker) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (rig.rendered().contains(marker))
                return;
            Thread.sleep(10);
        }
        fail("writer never emitted " + marker + "; got: " + rig.rendered());
    }

    @Test
    void consecutiveBlankLinesCollapseToOne() throws Exception {
        try (Rig rig = rig()) {
            rig.coordinator().start(new PromptSnapshot(List.of(""), 0, 0, List.of()));
            rig.coordinator().appendLines(lines("alpha", "", "", "", "omega"));
            settle(rig, "omega");

            var body = rig.scrollback().stream().dropWhile(l -> !l.contains("alpha"))
                                            .takeWhile(l -> !l.contains("omega")).toList();
            long blanks = body.stream().filter(String::isBlank).count();
            assertEquals(1, blanks, "three blanks between two lines must render as one: " + body);
        }
    }

    @Test
    void blanksSpanningSeparateAppendsAlsoCollapse() throws Exception {
        // The point of putting the rule here: two blocks each add their own blank without knowing about each other.
        try (Rig rig = rig()) {
            rig.coordinator().start(new PromptSnapshot(List.of(""), 0, 0, List.of()));
            rig.coordinator().appendLines(lines("first", ""));
            rig.coordinator().appendLines(lines("", "second"));
            settle(rig, "second");

            var body = rig.scrollback().stream().dropWhile(l -> !l.contains("first"))
                                            .takeWhile(l -> !l.contains("second")).toList();
            assertEquals(1, body.stream().filter(String::isBlank).count(), "one blank across the seam: " + body);
        }
    }

    @Test
    void theSessionNeverOpensWithABlankLine() throws Exception {
        try (Rig rig = rig()) {
            rig.coordinator().start(new PromptSnapshot(List.of(""), 0, 0, List.of()));
            rig.coordinator().appendLines(lines("", "", "content"));
            settle(rig, "content");

            var upTo = rig.scrollback().stream().takeWhile(l -> !l.contains("content")).filter(String::isBlank).count();
            assertEquals(0, upTo, "a leading blank wastes the first row: " + rig.scrollback());
        }
    }

    @Test
    void styledPaddingIsContentNotABlank() throws Exception {
        // A code block's background padding is visually a filled row; collapsing it would eat the block's shape.
        try (Rig rig = rig()) {
            rig.coordinator().start(new PromptSnapshot(List.of(""), 0, 0, List.of()));
            String padded = AnsiTheme.dark().styled("diffAddLine", "   ");
            rig.coordinator().appendLines(List.of(StyledLine.of("before"), StyledLine.of(padded), StyledLine.of(padded),
                                            StyledLine.of("after")));
            settle(rig, "after");

            var rows = rig.scrollback();
            assertEquals(2, rows.stream().filter(r -> r.equals("   ")).count(), "both padded rows survive; only "
                                            + "literally-empty text collapses: " + rows);
        }
    }

    @Test
    void everyAppendedLineArrivesInOrder() throws Exception {
        try (Rig rig = rig()) {
            rig.coordinator().start(new PromptSnapshot(List.of(""), 0, 0, List.of()));
            for (int i = 0; i < 40; i++)
                rig.coordinator().appendLines(lines(String.format("line-%02d", i)));
            settle(rig, "line-39");

            // Match tokens, not whole rows: erasing and redrawing the prompt can leave a divider on the same output
            // row as the next appended line, which says nothing about the order the lines were committed in.
            var seen = new ArrayList<String>();
            var token = Pattern.compile("line-\\d\\d").matcher(rig.rendered());
            while (token.find())
                seen.add(token.group());
            var expected = new ArrayList<String>();
            for (int i = 0; i < 40; i++)
                expected.add(String.format("line-%02d", i));
            assertEquals(expected, seen, "the writer must not reorder or drop a batch");
        }
    }

    /**
     * Appends come from the agent thread while prompt redraws come from the input thread; the queue is the only thing
     * keeping them from interleaving mid-frame. Nothing may be lost or duplicated under that contention.
     */
    @Test
    void concurrentAppendsAndPromptRedrawsLoseNothing() throws Exception {
        try (Rig rig = rig()) {
            rig.coordinator().start(new PromptSnapshot(List.of(""), 0, 0, List.of()));
            int writers = 4, each = 25;
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(writers + 1)) {
                for (int w = 0; w < writers; w++) {
                    int id = w;
                    pool.submit(() -> {
                        start.await();
                        for (int i = 0; i < each; i++)
                            rig.coordinator().appendLines(lines(String.format("w%d-%02d", id, i)));
                        return null;
                    });
                }
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 50; i++)
                        rig.coordinator().redrawPrompt(new PromptSnapshot(List.of("typing " + i), 0, 0, List.of()));
                    return null;
                });
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
            }
            settle(rig, "w3-24");

            String out = rig.rendered();
            for (int w = 0; w < writers; w++)
                for (int i = 0; i < each; i++) {
                    String token = String.format("w%d-%02d", w, i);
                    assertEquals(1, countOccurrences(out, token), token + " appeared " + countOccurrences(out, token)
                                                    + " times; every line is written exactly once");
                }
        }
    }

    @Test
    void closeIsIdempotentAndStopsTheWriter() throws Exception {
        Rig rig = rig();
        rig.coordinator().start(new PromptSnapshot(List.of(""), 0, 0, List.of()));
        rig.coordinator().appendLines(lines("before-close"));
        settle(rig, "before-close");

        rig.coordinator().close();
        assertDoesNotThrow(() -> rig.coordinator().close(), "a second close must be a no-op, not a hang or a throw");
        assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals("pijota-tui-writer")
                                        && t.isAlive() && t.getState() != Thread.State.TERMINATED
                                        && countOccurrences(rig.rendered(), "before-close") == 0),
                                        "the writer thread should have stopped");
        rig.toTerminal().close();
        rig.terminal().close();
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length()))
            n++;
        return n;
    }
}
