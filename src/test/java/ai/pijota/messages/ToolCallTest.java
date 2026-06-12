package ai.pijota.messages;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallTest {

    @Test
    void storesFields() {
        var tc = new ToolCall("id-1", "read", "{\"file\":\"foo.txt\"}");
        assertEquals("id-1", tc.id());
        assertEquals("read", tc.name());
        assertEquals("{\"file\":\"foo.txt\"}", tc.arguments());
    }

    @Test
    void equalityByFields() {
        var a = new ToolCall("id", "name", "args");
        var b = new ToolCall("id", "name", "args");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void emptyArguments() {
        var tc = new ToolCall("id", "name", "");
        assertEquals("", tc.arguments());
    }
}
