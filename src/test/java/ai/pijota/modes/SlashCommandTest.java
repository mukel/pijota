package ai.pijota.modes;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlashCommandTest {

    @Test
    void storesRecordFields() {
        var cmd = new SlashCommand("/help", "show help", List.of("/h", "/?"), s -> {});
        assertEquals("/help", cmd.name());
        assertEquals("show help", cmd.description());
        assertEquals(List.of("/h", "/?"), cmd.aliases());
        assertNotNull(cmd.handler());
    }

    @Test
    void emptyAliases() {
        var cmd = new SlashCommand("/quit", "exit", List.of(), s -> {});
        assertTrue(cmd.aliases().isEmpty());
    }

    @Test
    void handlerReceivesInput() {
        var ref = new String[1];
        var cmd = new SlashCommand("/echo", "echo", List.of(), s -> ref[0] = s);
        cmd.handler().accept("hello");
        assertEquals("hello", ref[0]);
    }

    @Test
    void equalityByFields() {
        var handler = (java.util.function.Consumer<String>) s -> {};
        var a = new SlashCommand("/a", "desc", List.of(), handler);
        var b = new SlashCommand("/a", "desc", List.of(), handler);
        assertEquals(a, b);
    }

    @Test
    void notEqualWhenNameDiffers() {
        var handler = (java.util.function.Consumer<String>) s -> {};
        var a = new SlashCommand("/a", "desc", List.of(), handler);
        var b = new SlashCommand("/b", "desc", List.of(), handler);
        assertNotEquals(a, b);
    }
}
