package org.pijota.web;

import org.junit.jupiter.api.Test;
import org.pijota.tools.SlashCommandTool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WebUiContextTest {

    /** Captures the most recent emitted (event, data) pair so dialogs can be inspected and answered. */
    private static final class Bus {
        String event;
        Map<String, Object> data;

        WebUiContext ui() {
            return new WebUiContext((ev, d) -> {
                event = ev;
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) d;
                data = map;
            });
        }
    }

    @Test
    void confirmCompletesWithTheTappedAnswer() {
        var bus = new Bus();
        var ui = bus.ui();
        var future = ui.confirm("Proceed?");

        assertEquals("dialog", bus.event);
        assertEquals("confirm", bus.data.get("kind"));
        assertFalse(future.isDone());

        ui.resolve((String) bus.data.get("id"), "yes");
        assertTrue(future.join());
    }

    @Test
    void confirmIsFalseOnNo() {
        var bus = new Bus();
        var ui = bus.ui();
        var future = ui.confirm("Delete?");
        ui.resolve((String) bus.data.get("id"), "no");
        assertFalse(future.join());
    }

    @Test
    void promptReturnsTheTypedText() {
        var bus = new Bus();
        var ui = bus.ui();
        var future = ui.prompt("Name?", "default-name");

        assertEquals("prompt", bus.data.get("kind"));
        assertEquals("default-name", bus.data.get("default"));

        ui.resolve((String) bus.data.get("id"), "typed value");
        assertEquals("typed value", future.join());
    }

    @Test
    void selectMapsTheChosenIndexBackToTheDomainObject() {
        var bus = new Bus();
        var ui = bus.ui();
        var items = List.of("alpha", "beta", "gamma");
        var chosen = new AtomicReference<String>();

        ui.select("Pick one", items, s -> s.toUpperCase(), chosen::set);

        assertEquals("select", bus.data.get("kind"));
        assertEquals(List.of("ALPHA", "BETA", "GAMMA"), bus.data.get("options")); // labels via the render fn

        ui.resolve((String) bus.data.get("id"), "1"); // tapped the 2nd option
        assertEquals("beta", chosen.get()); // caller gets its own object, never the index
    }

    @Test
    void selectIgnoresAnOutOfRangeIndex() {
        var bus = new Bus();
        var ui = bus.ui();
        var chosen = new AtomicReference<String>();
        ui.select("Pick", List.of("only"), s -> s, chosen::set);

        ui.resolve((String) bus.data.get("id"), "9"); // no such option
        assertNull(chosen.get());
    }

    @Test
    void askQuestionsEmitsOneQuestionDialogAndResolvesWithTheAnswer() {
        var bus = new Bus();
        var ui = bus.ui();
        var question = new SlashCommandTool.Question("Pick one?", "Choice",
                                        List.of(new SlashCommandTool.Option("A", "first"),
                                                                        new SlashCommandTool.Option("B", "second")),
                                        false);

        var future = ui.askQuestions(List.of(question)); // one combined dialog, not a select per question

        assertEquals("dialog", bus.event);
        assertEquals("question", bus.data.get("kind"));
        assertEquals(1, ((List<?>) bus.data.get("questions")).size());
        assertFalse(future.isDone(), "stays pending until the browser answers");

        ui.resolve((String) bus.data.get("id"), "Pick one? → A");
        assertEquals("Pick one? → A", future.join());
    }

    @Test
    void dialogsAreReplayablyPendingUntilAnsweredThenSignalOtherTabsToClose() {
        // Regression: a tab opened mid-question saw "running" but no dialog. Pending dialogs must be replayable, and
        // answering must tell other tabs to close.
        var bus = new Bus();
        var ui = bus.ui();
        var future = ui.confirm("Proceed?");

        assertEquals(1, ui.pendingDialogs().size(), "an unanswered dialog is replayable to a newly-connected tab");
        assertEquals("confirm", ui.pendingDialogs().get(0).get("kind"));

        ui.resolve((String) bus.data.get("id"), "yes");
        assertTrue(ui.pendingDialogs().isEmpty(), "an answered dialog is no longer pending");
        assertEquals("dialog_done", bus.event, "other tabs are told to close it");
        assertTrue(future.join());
    }

    @Test
    void cancelPendingUnblocksToolsWaitingOnDialogs() {
        // Regression: stopping remote control while a /question was pending hung the turn forever. cancelPending() must
        // complete every waiting future so the blocked tool returns.
        var bus = new Bus();
        var ui = bus.ui();
        var confirm = ui.confirm("Proceed?");
        var question = ui.askQuestions(List.of(new SlashCommandTool.Question("Pick?", "",
                                        List.of(new SlashCommandTool.Option("A", "")), false)));

        assertFalse(confirm.isDone());
        assertFalse(question.isDone());

        ui.cancelPending(); // the frontend (remote-control channel) went away

        assertTrue(confirm.isDone() && question.isDone(), "all waiting tools are unblocked");
        assertFalse(confirm.join(), "cancelled confirm → safe default (no)");
        assertEquals("", question.join(), "cancelled question → empty answer");
        assertTrue(ui.pendingDialogs().isEmpty());
    }

    @Test
    void themeIsNeverNullSoStyledIsSafe() {
        // Regression: /transcend (and any extension) calls ui.theme().styled(...). Under remote control the UI owner is
        // the web/Telegram context; its theme() must not be null. A no-color theme returns the text unchanged.
        var ui = new Bus().ui();
        assertNotNull(ui.theme());
        assertEquals("hello", ui.theme().styled("toolTitle", "hello"));
    }

    @Test
    void resolveIsANoOpForUnknownOrAlreadyAnsweredDialogs() {
        var bus = new Bus();
        var ui = bus.ui();
        var future = ui.confirm("once?");
        String id = (String) bus.data.get("id");

        ui.resolve(id, "yes");
        assertTrue(future.join());
        assertDoesNotThrow(() -> ui.resolve(id, "no")); // second answer dropped, no exception
        assertDoesNotThrow(() -> ui.resolve("does-not-exist", "x"));
        assertDoesNotThrow(() -> ui.resolve(null, "x"));
    }
}
