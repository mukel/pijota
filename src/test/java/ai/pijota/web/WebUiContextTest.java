package ai.pijota.web;

import org.junit.jupiter.api.Test;

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
