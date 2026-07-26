package org.pijota.acp;

import org.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the JSON-RPC transport over in-memory streams: dispatch, framing, errors, and outbound correlation. */
class AcpPeerTest {

    /** Runs a peer over {@code input} (until EOF) and returns the parsed output frames. */
    private static List<JsonNode> run(String input, Consumer<AcpPeer> setup) throws Exception {
        var out = new ByteArrayOutputStream();
        var peer = new AcpPeer(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        setup.accept(peer);
        peer.run();
        return frames(out);
    }

    private static List<JsonNode> frames(ByteArrayOutputStream out) throws Exception {
        var list = new ArrayList<JsonNode>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n"))
            if (!line.isBlank())
                list.add(Util.MAPPER.readTree(line));
        return list;
    }

    private static JsonNode json(String s) {
        try {
            return Util.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String request(String method, String id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\"}\n";
    }

    @Test
    void incomingRequestIsAnsweredWithResult() throws Exception {
        var out = run("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\",\"params\":{}}\n", p -> p.onRequest("ping",
                                        req -> CompletableFuture.completedFuture(json("{\"pong\":true}"))));
        assertEquals(1, out.size());
        assertEquals("2.0", out.get(0).get("jsonrpc").asText());
        assertEquals(7, out.get(0).get("id").asInt());
        assertTrue(out.get(0).path("result").path("pong").asBoolean());
    }

    @Test
    void unknownMethodYieldsMethodNotFound() throws Exception {
        var out = run(request("does/notexist", "1"), p -> {});
        assertEquals(-32601, out.get(0).path("error").path("code").asInt());
    }

    @Test
    void malformedLineYieldsParseErrorWithNullId() throws Exception {
        var out = run("{ not valid json\n", p -> {});
        assertEquals(-32700, out.get(0).path("error").path("code").asInt());
        assertTrue(out.get(0).get("id").isNull());
    }

    @Test
    void handlerFailureYieldsInternalError() throws Exception {
        var out = run(request("m", "1"), p -> p.onRequest("m",
                                        req -> CompletableFuture.failedFuture(new RuntimeException("boom"))));
        assertEquals(-32603, out.get(0).path("error").path("code").asInt());
        assertEquals("boom", out.get(0).path("error").path("message").asText());
    }

    @Test
    void notificationInvokesHandlerAndWritesNothing() throws Exception {
        var seen = new AtomicReference<String>();
        var out = run("{\"jsonrpc\":\"2.0\",\"method\":\"note\",\"params\":{\"x\":\"y\"}}\n",
                                        p -> p.onNotification("note", params -> seen.set(params.path("x").asText())));
        assertEquals("y", seen.get());
        assertTrue(out.isEmpty(), "a notification must not get a response");
    }

    @Test
    void blankLinesAreSkipped() throws Exception {
        var out = run("\n \n" + request("ping", "1") + "\n", p -> p.onRequest("ping",
                                        req -> CompletableFuture.completedFuture(json("\"ok\""))));
        assertEquals(1, out.size());
        assertEquals("ok", out.get(0).path("result").asText());
    }

    @Test
    void stringIdIsEchoedBack() throws Exception {
        var out = run("{\"jsonrpc\":\"2.0\",\"id\":\"abc-1\",\"method\":\"ping\"}\n", p -> p.onRequest("ping",
                                        req -> CompletableFuture.completedFuture(json("\"ok\""))));
        assertEquals("abc-1", out.get(0).get("id").asText());
    }

    @Test
    void outboundRequestGetsIncrementingIdsAndParams() throws Exception {
        var out = new ByteArrayOutputStream();
        var peer = new AcpPeer(new ByteArrayInputStream(new byte[0]), out);
        peer.request("a", json("{\"k\":1}"));
        peer.request("b", null);
        var f = frames(out);
        assertEquals(1, f.get(0).get("id").asInt());
        assertEquals("a", f.get(0).get("method").asText());
        assertEquals(1, f.get(0).path("params").path("k").asInt());
        assertEquals(2, f.get(1).get("id").asInt());
        assertFalse(f.get(1).has("params"));
    }

    @Test
    void notifyWritesMethodWithoutId() throws Exception {
        var out = new ByteArrayOutputStream();
        var peer = new AcpPeer(new ByteArrayInputStream(new byte[0]), out);
        peer.notify("session/update", json("{\"x\":1}"));
        var f = frames(out).get(0);
        assertFalse(f.has("id"));
        assertEquals("session/update", f.get("method").asText());
        assertEquals(1, f.path("params").path("x").asInt());
    }

    @Test
    void incomingResponseCompletesPendingOutboundRequest() throws Exception {
        var out = new ByteArrayOutputStream();
        var peer = new AcpPeer(new ByteArrayInputStream("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":42}}\n"
                                        .getBytes(StandardCharsets.UTF_8)), out);
        var fut = peer.request("test/method", null); // outbound id is 1
        peer.run(); // reads the staged response
        assertEquals(42, fut.getNow(null).path("ok").asInt());
    }

    @Test
    void incomingErrorResponseFailsPendingRequest() throws Exception {
        var out = new ByteArrayOutputStream();
        var peer = new AcpPeer(new ByteArrayInputStream(
                                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-1,\"message\":\"no\"}}\n"
                                                                        .getBytes(StandardCharsets.UTF_8)),
                                        out);
        var fut = peer.request("m", null);
        peer.run();
        assertTrue(fut.isCompletedExceptionally());
        var ex = assertThrows(Exception.class, fut::get);
        assertInstanceOf(AcpPeer.AcpRemoteException.class, ex.getCause());
    }

    @Test
    void onRespondedFiresWithMethodAfterAResponse() throws Exception {
        var events = new ArrayList<String>();
        var out = run(request("session/new", "5"), p -> {
            p.onRequest("session/new", req -> CompletableFuture.completedFuture(json("{\"sessionId\":\"s\"}")));
            p.onResponded(events::add);
        });
        assertEquals(List.of("session/new"), events);
        assertEquals("s", out.get(0).path("result").path("sessionId").asText());
    }

    @Test
    void onRespondedDoesNotFireForErrors() throws Exception {
        var events = new ArrayList<String>();
        run(request("nope", "1"), p -> p.onResponded(events::add));
        assertTrue(events.isEmpty(), "the ordering hook is only for successful responses");
    }
}
