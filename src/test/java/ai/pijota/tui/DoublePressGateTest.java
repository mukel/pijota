package ai.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DoublePressGateTest {

    @Test
    void firstPressReturnsFalse() {
        var gate = new DoublePressGate(1_000_000);
        assertFalse(gate.tryFire());
    }

    @Test
    void secondPressWithinWindowReturnsTrue() {
        var gate = new DoublePressGate(60_000);
        assertFalse(gate.tryFire());
        assertTrue(gate.tryFire());
    }

    @Test
    void thirdPressReturnsFalseAgain() {
        var gate = new DoublePressGate(60_000);
        assertFalse(gate.tryFire());
        assertTrue(gate.tryFire());
        assertFalse(gate.tryFire());
    }

    @Test
    void disarmPreventsTrigger() {
        var gate = new DoublePressGate(60_000);
        assertFalse(gate.tryFire());
        gate.disarm();
        assertFalse(gate.tryFire());
    }

    @Test
    void disarmWhenAlreadyDisarmedDoesNothing() {
        var gate = new DoublePressGate(60_000);
        gate.disarm();
        assertFalse(gate.tryFire());
    }

    @Test
    void expiredWindowTreatsAsNewFirstPress() {
        var gate = new DoublePressGate(1); // 1ms window
        assertFalse(gate.tryFire());
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertFalse(gate.tryFire());
    }

    @Test
    void zeroMsWindow() {
        var gate = new DoublePressGate(0);
        assertFalse(gate.tryFire());
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertFalse(gate.tryFire());
    }
}
