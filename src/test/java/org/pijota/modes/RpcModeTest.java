package org.pijota.modes;

import org.pijota.messages.Message;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class RpcModeTest {

    private InputStream originalIn;
    private PrintStream originalOut;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void captureStreams() {
        originalIn = System.in;
        originalOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setIn(originalIn);
    }

    private void feedStdin(String text) {
        System.setIn(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parseRequestWithAllFields() {
        var req = RpcMode.parseRpcRequest("{\"method\":\"prompt\",\"id\":\"1\",\"params\":{\"prompt\":\"hello\"}}");
        assertNotNull(req);
        assertEquals("prompt", req.method());
        assertEquals("1", req.id());
        assertEquals("hello", req.params());
    }

    @Test
    void parseRequestWithoutId() {
        var req = RpcMode.parseRpcRequest("{\"method\":\"ping\"}");
        assertNotNull(req);
        assertEquals("ping", req.method());
        assertNull(req.id());
        assertEquals("", req.params());
    }

    @Test
    void parseInvalidJson() {
        assertNull(RpcMode.parseRpcRequest("not json"));
    }

    @Test
    void parseEmptyString() {
        assertNull(RpcMode.parseRpcRequest(""));
    }

    @Test
    void responseContentAssistantMessages() {
        var msgs = List.<Message>of(new Message.Assistant("Hello", List.of(), ""),
                                        new Message.Assistant("World", List.of(), ""));
        var result = RpcMode.responseContent(msgs);
        assertEquals("HelloWorld", result);
    }

    @Test
    void responseContentIgnoresNonAssistant() {
        var msgs = List.<Message>of(new Message.User("question"), new Message.Assistant("answer", List.of(), ""));
        var result = RpcMode.responseContent(msgs);
        assertEquals("answer", result);
    }

    @Test
    void responseContentSkipsBlankContent() {
        var msgs = List.<Message>of(new Message.Assistant("", List.of(), ""),
                                        new Message.Assistant("real content", List.of(), ""));
        var result = RpcMode.responseContent(msgs);
        assertEquals("real content", result);
    }

    @Test
    void responseContentEmpty() {
        assertEquals("", RpcMode.responseContent(List.of()));
    }

    @Test
    void runPromptReturnsResultAndShutdown() {
        feedStdin("{\"method\":\"prompt\",\"id\":\"7\",\"params\":{\"prompt\":\"hi\"}}\n"
                                        + "{\"method\":\"shutdown\",\"id\":\"8\"}\n");
        RpcMode.run(prompt -> CompletableFuture
                                        .completedFuture(List.of(new Message.Assistant("hello", List.of(), ""))));
        String out = captured.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("\"id\":7"));
        assertTrue(out.contains("\"result\":\"hello\""));
        assertTrue(out.contains("\"id\":8"));
    }

    @Test
    void runPingReturnsPong() {
        feedStdin("{\"method\":\"ping\",\"id\":\"1\"}\n{\"method\":\"shutdown\",\"id\":\"2\"}\n");
        RpcMode.run(ignored -> CompletableFuture.completedFuture(List.of()));
        String out = captured.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("\"id\":1"));
        assertTrue(out.contains("\"result\":\"pong\""));
    }

    @Test
    void runUnknownMethodReturnsError() {
        feedStdin("{\"method\":\"unknown\",\"id\":\"3\"}\n{\"method\":\"shutdown\",\"id\":\"4\"}\n");
        RpcMode.run(ignored -> CompletableFuture.completedFuture(List.of()));
        String out = captured.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("\"id\":3"));
        assertTrue(out.contains("\"error\""));
        assertTrue(out.contains("Method not found"));
    }

    @Test
    void runBlankLinesAreIgnored() {
        feedStdin("\n\n{\"method\":\"shutdown\",\"id\":\"5\"}\n");
        RpcMode.run(ignored -> CompletableFuture.completedFuture(List.of()));
        String out = captured.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("\"id\":5"));
    }

    @Test
    void runInvalidJsonIsIgnored() {
        feedStdin("not json\n{\"method\":\"shutdown\",\"id\":\"6\"}\n");
        RpcMode.run(ignored -> CompletableFuture.completedFuture(List.of()));
        String out = captured.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("\"id\":6"));
        assertFalse(out.contains("\"id\":null"));
    }
}
