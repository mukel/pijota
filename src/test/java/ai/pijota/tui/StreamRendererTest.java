package ai.pijota.tui;

import ai.pijota.testkit.AnsiStripper;
import ai.pijota.theme.AnsiTheme;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamRendererTest {

    /** Capturing sink: records every line appended so tests can assert on visible (ANSI-stripped) text. */
    private static final class CapturingSink implements StreamRenderer.Output {
        final List<String> lines = new ArrayList<>();

        @Override
        public void appendLines(List<StyledLine> styled) {
            for (StyledLine line : styled)
                lines.add(AnsiStripper.strip(line.text()));
        }

        String text() {
            return String.join("\n", lines);
        }
    }

    private static StreamRenderer renderer(CapturingSink sink, boolean showThinking) {
        var theme = AnsiTheme.dark();
        return new StreamRenderer(sink, theme, theme.thinking(), () -> 80, () -> showThinking, () -> "high",
                                        MarkdownStyle.PLAIN);
    }

    @Test
    void streamsContentToSinkOnFlush() {
        var sink = new CapturingSink();
        var r = renderer(sink, false);

        r.streamContent("Hello");
        r.streamContent("Hello world");
        r.flush();

        assertTrue(sink.text().contains("Hello world"), sink.text());
    }

    @Test
    void thinkingShowsHeaderAndContentClosesItWithSummary() {
        var sink = new CapturingSink();
        var r = renderer(sink, true);

        r.streamThinking("considering options");
        r.streamContent("the answer");
        r.flush();

        String out = sink.text();
        assertTrue(out.contains("thinking"), "thinking header expected: " + out);
        assertTrue(out.contains("thought"), "thinking summary expected: " + out);
        assertTrue(out.contains("the answer"), "content expected: " + out);
    }

    @Test
    void thinkingBodyTextIsRendered() {
        var sink = new CapturingSink();
        var r = renderer(sink, true);

        r.streamThinking("first thought\n");
        r.streamThinking("first thought\nsecond thought");
        r.streamContent("the answer");
        r.flush();

        String out = sink.text();
        assertTrue(out.contains("first thought"), "thinking body line 1 missing: " + out);
        assertTrue(out.contains("second thought"), "thinking body line 2 missing: " + out);
    }

    @Test
    void thinkingBodyRendersWhenBlockClosesNotDeferredToFlush() {
        var sink = new CapturingSink();
        var r = renderer(sink, true);

        r.streamThinking("reasoning one\n");
        r.streamThinking("reasoning one\nreasoning two\n");
        r.streamContent("answer"); // first content closes the thinking block

        // The thinking body must already be emitted when the block closes — not held buffered until the final flush.
        String beforeFlush = sink.text();
        assertTrue(beforeFlush.contains("reasoning one"),
                                        "thinking body not rendered when block closed: " + beforeFlush);
        assertTrue(beforeFlush.contains("reasoning two"),
                                        "thinking body not rendered when block closed: " + beforeFlush);

        r.flush();
        String out = sink.text();
        assertTrue(out.indexOf("reasoning one") < out.indexOf("answer"), "thinking must precede the answer: " + out);
    }

    @Test
    void resetClearsDeltaTrackingSoContentReappears() {
        var sink = new CapturingSink();
        var r = renderer(sink, false);

        r.streamContent("first answer");
        r.flush();
        r.reset();
        // Same text again would be a no-op delta if state had not been reset; after reset it must render.
        r.streamContent("first answer");
        r.flush();

        long occurrences = sink.lines.stream().filter(l -> l.contains("first answer")).count();
        assertEquals(2, occurrences, sink.text());
    }

    @Test
    void compactionStreamAndSummaryEmitMarkers() {
        var sink = new CapturingSink();
        var r = renderer(sink, false);

        assertFalse(r.compactionStreamRendered());
        r.renderCompactionStream("partial summary\n");
        assertTrue(r.compactionStreamRendered());
        r.renderCompactionSummary("final summary");

        String out = sink.text();
        assertTrue(out.contains("compacting"), out);
        assertTrue(out.contains("compacted"), out);
        assertTrue(out.contains("final summary"), out);
    }

    @Test
    void contentLiveTailReflectsPartialAndClearsOnCommit() {
        var r = renderer(new CapturingSink(), false);

        assertTrue(r.contentLiveTail().isEmpty(), "nothing streamed yet");
        r.streamContent("Hel");
        assertEquals("Hel", r.contentLiveTail().orElseThrow());
        r.streamContent("Hello\n"); // the newline commits the line
        assertTrue(r.contentLiveTail().isEmpty(), "committed line must not preview");
        r.streamContent("Hello\nWor");
        assertEquals("Wor", r.contentLiveTail().orElseThrow());
        r.flush();
        assertTrue(r.contentLiveTail().isEmpty(), "flush commits the partial");
    }

    @Test
    void contentLiveTailSuppressesReasoningMetaLine() {
        var r = renderer(new CapturingSink(), false);

        r.streamContent("Let me check the code"); // "let me " is a reasoning-meta prefix, dropped on commit
        assertTrue(r.contentLiveTail().isEmpty(), "reasoning-meta partial must not preview");
    }

    @Test
    void thinkingIsGatedOutOfFlushWhenHidden() {
        var sink = new CapturingSink();
        var r = renderer(sink, false); // showThinking == false

        // Even if a partial thinking line is buffered, flush must not emit it when thinking is hidden.
        r.streamThinking("secret reasoning");
        sink.lines.clear();
        r.flush();

        assertFalse(sink.text().contains("secret reasoning"), sink.text());
    }
}
