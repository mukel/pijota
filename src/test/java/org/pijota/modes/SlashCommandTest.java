package org.pijota.modes;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

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
        var handler = (Consumer<String>) s -> {};
        var a = new SlashCommand("/a", "desc", List.of(), handler);
        var b = new SlashCommand("/a", "desc", List.of(), handler);
        assertEquals(a, b);
    }

    @Test
    void notEqualWhenNameDiffers() {
        var handler = (Consumer<String>) s -> {};
        var a = new SlashCommand("/a", "desc", List.of(), handler);
        var b = new SlashCommand("/b", "desc", List.of(), handler);
        assertNotEquals(a, b);
    }

    @Test
    void argsOfReturnsTextAfterTheCommandWord() {
        assertEquals("gpt-4", SlashCommand.argsOf("/model gpt-4"));
        assertEquals("arg1 arg2", SlashCommand.argsOf("/cmd arg1 arg2"));
        assertEquals("what is X", SlashCommand.argsOf("/btw what is X"));
        assertEquals("sub/command", SlashCommand.argsOf("/load sub/command"));
    }

    @Test
    void argsOfEmptyWhenNoArgs() {
        assertEquals("", SlashCommand.argsOf("/help"));
        assertEquals("", SlashCommand.argsOf("/quit"));
        assertEquals("", SlashCommand.argsOf(null));
    }

    @Test
    void argsOfPreservesInnerCaseAndStripsOuterWhitespace() {
        assertEquals("Dark Mode", SlashCommand.argsOf("/theme Dark Mode"));
        assertEquals("a b", SlashCommand.argsOf("  /cmd   a b  "));
    }

    @Test
    void argsOfIsTabTolerant() {
        // the previous Telegram parser split on ' ' only and lost args after a tab
        assertEquals("foo", SlashCommand.argsOf("/cmd\tfoo"));
    }

    @Test
    void matchesNameAndAliasesIgnoringSlashAndCase() {
        var c = new SlashCommand("logs", "Open log", List.of("log", "/tail"), s -> {});
        assertTrue(c.matches("logs"));
        assertTrue(c.matches("log"));
        assertTrue(c.matches("tail"), "alias with a leading slash still matches");
        assertTrue(c.matches("/logs"), "a leading slash on the input is ignored");
        assertTrue(c.matches("LOGS"), "case-insensitive");
        assertFalse(c.matches("logz"));
    }
}
