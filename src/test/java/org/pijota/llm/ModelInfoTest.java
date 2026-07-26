package org.pijota.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelInfoTest {

    @Test
    void storesRecordFields() {
        var info = new ModelInfo("gpt-4o", "openai", "GPT-4o", 128_000);
        assertEquals("gpt-4o", info.id());
        assertEquals("openai", info.provider());
        assertEquals("GPT-4o", info.displayName());
        assertEquals(128_000, info.contextWindow());
    }

    @Test
    void equalityByFields() {
        var a = new ModelInfo("a", "b", "c", 100);
        var b = new ModelInfo("a", "b", "c", 100);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualWhenDiffer() {
        var a = new ModelInfo("a", "b", "c", 100);
        var b = new ModelInfo("a", "b", "c", 200);
        assertNotEquals(a, b);
    }

    @Test
    void zeroContextWindow() {
        var info = new ModelInfo("tiny", "local", "Tiny Model", 0);
        assertEquals(0, info.contextWindow());
    }

    @Test
    void toStringIncludesFields() {
        var info = new ModelInfo("id", "prov", "Name", 4096);
        var s = info.toString();
        assertTrue(s.contains("id"));
        assertTrue(s.contains("prov"));
    }
}
