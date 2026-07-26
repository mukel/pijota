package org.pijota.acp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the "pose as text, answer via next prompt" UiContext — its emit sink and pending-reply state machine. */
class AcpUiContextTest {

    private final List<String> emitted = new ArrayList<>();
    private final AcpUiContext ui = new AcpUiContext(emitted::add);

    private String transcript() {
        return String.join("\n", emitted);
    }

    @Test
    void outputCallbacksEmitText() {
        ui.setStatus("status-line");
        ui.alert("an alert");
        ui.appendOutput(List.of("l1", "l2"));
        assertTrue(emitted.contains("status-line"));
        assertTrue(emitted.contains("an alert"));
        assertTrue(emitted.contains("l1") && emitted.contains("l2"));
    }

    @Test
    void confirmYesResolvesTrue() {
        var f = ui.confirm("proceed?");
        assertTrue(transcript().contains("proceed?"));
        assertTrue(ui.tryAnswer("yes"));
        assertTrue(f.getNow(null));
    }

    @Test
    void confirmNoResolvesFalse() {
        var f = ui.confirm("proceed?");
        assertTrue(ui.tryAnswer("n"));
        assertFalse(f.getNow(Boolean.TRUE));
    }

    @Test
    void confirmUnrecognizedReplyFallsThrough() {
        var f = ui.confirm("proceed?");
        assertFalse(ui.tryAnswer("maybe")); // not consumed → handled as a normal message
        assertFalse(f.isDone());
    }

    @Test
    void selectByNumberInvokesChoice() {
        var chosen = new AtomicReference<String>();
        ui.select("Pick one", List.of("alpha", "beta", "gamma"), s -> s, chosen::set);
        assertTrue(transcript().contains("Pick one"));
        assertTrue(transcript().contains("2. beta"));
        assertTrue(ui.tryAnswer("2"));
        assertEquals("beta", chosen.get());
    }

    @Test
    void selectCancelChoosesNothing() {
        var chosen = new AtomicReference<String>();
        ui.select("Pick", List.of("a", "b"), s -> s, chosen::set);
        assertTrue(ui.tryAnswer("cancel"));
        assertNull(chosen.get());
    }

    @Test
    void selectOutOfRangeIsConsumedWithHint() {
        var chosen = new AtomicReference<String>();
        ui.select("Pick", List.of("a", "b"), s -> s, chosen::set);
        assertTrue(ui.tryAnswer("9"));
        assertNull(chosen.get());
        assertTrue(transcript().contains("between 1 and 2"));
    }

    @Test
    void selectNonNumberFallsThrough() {
        var chosen = new AtomicReference<String>();
        ui.select("Pick", List.of("a", "b"), s -> s, chosen::set);
        assertFalse(ui.tryAnswer("hello"));
        assertNull(chosen.get());
    }

    @Test
    void emptySelectIsANoOp() {
        ui.select("Pick", List.<String>of(), s -> s, s -> fail("must not choose from an empty list"));
        assertFalse(ui.tryAnswer("1")); // nothing was armed
    }

    @Test
    void cancelPendingResolvesOutstandingConfirmAsFalse() {
        var f = ui.confirm("proceed?");
        ui.cancelPending();
        assertFalse(f.getNow(Boolean.TRUE));
        assertFalse(ui.tryAnswer("yes")); // pending was cleared
    }

    @Test
    void tryAnswerWithNothingPendingIsFalse() {
        assertFalse(ui.tryAnswer("anything"));
    }
}
