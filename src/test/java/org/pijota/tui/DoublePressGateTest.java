package org.pijota.tui;

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
    void expiredWindowTreatsAsNewFirstPress() throws InterruptedException {
        var gate = new DoublePressGate(50);
        assertFalse(gate.tryFire());
        Thread.sleep(100);
        assertFalse(gate.tryFire());
    }

    @Test
    void zeroMsWindow() throws InterruptedException {
        var gate = new DoublePressGate(0);
        assertFalse(gate.tryFire());
        Thread.sleep(10);
        assertFalse(gate.tryFire());
    }
}
