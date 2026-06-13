package ai.pijota.tui;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextTokenMeterTest {

    @Test
    void cachesWithinTtlAndSameMessageCount() {
        var messages = new AtomicInteger(3);
        var estimateCalls = new AtomicInteger();
        var estimate = new AtomicLong(1000);
        var meter = new ContextTokenMeter(messages::get, () -> {
            estimateCalls.incrementAndGet();
            return estimate.get();
        }, 1000);

        assertEquals(1000, meter.tokens(0));
        estimate.set(2000); // would change if recomputed
        assertEquals(1000, meter.tokens(500), "within TTL and same message count -> cached");
        assertEquals(1, estimateCalls.get());
    }

    @Test
    void recomputesAfterTtlExpires() {
        var messages = new AtomicInteger(3);
        var estimate = new AtomicLong(1000);
        var meter = new ContextTokenMeter(messages::get, estimate::get, 1000);

        assertEquals(1000, meter.tokens(0));
        estimate.set(2000);
        assertEquals(2000, meter.tokens(1000), "TTL elapsed -> recompute");
    }

    @Test
    void recomputesWhenMessageCountChanges() {
        var messages = new AtomicInteger(3);
        var estimate = new AtomicLong(1000);
        var meter = new ContextTokenMeter(messages::get, estimate::get, 10_000);

        assertEquals(1000, meter.tokens(0));
        estimate.set(2000);
        messages.set(4); // transcript grew within TTL -> must recompute
        assertEquals(2000, meter.tokens(100));
    }
}
