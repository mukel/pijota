package org.pijota.extensions.mcp;

import org.pijota.testkit.Await;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class McpClientTest {

    private static final Duration SHORT = Duration.ofSeconds(2);

    private static McpClient connected(FakeMcpTransport transport) throws Exception {
        var client = new McpClient("fake", transport, Runnable::run);
        client.connect(SHORT);
        return client;
    }

    @Test
    void handshakeCompletesAndSendsInitializedNotification() throws Exception {
        var transport = new FakeMcpTransport(List.of(List.of()), (t, a) -> FakeMcpTransport.Outcome.result("x"));
        connected(transport);
        assertTrue(transport.sent().stream().anyMatch(l -> l.contains("notifications/initialized")),
                                        transport.sent().toString());
    }

    @Test
    void listsToolsAcrossPages() throws Exception {
        var page0 = List.of(FakeMcpTransport.tool("a", "tool a"));
        var page1 = List.of(FakeMcpTransport.tool("b", "tool b"));
        var transport = new FakeMcpTransport(List.of(page0, page1), (t, a) -> FakeMcpTransport.Outcome.result("x"));
        var client = connected(transport);

        List<McpToolDescriptor> tools = client.listTools(SHORT);
        assertEquals(List.of("a", "b"), tools.stream().map(McpToolDescriptor::name).toList());
    }

    @Test
    void callReturnsServerResultAndErrorFlag() throws Exception {
        FakeMcpTransport.CallHandler handler = (tool, args) -> "boom".equals(tool)
                                        ? FakeMcpTransport.Outcome.error("nope")
                                        : FakeMcpTransport.Outcome.result("ok:" + args.path("v").asText(""));
        var transport = new FakeMcpTransport(List.of(List.of()), handler);
        var client = connected(transport);

        McpCallResult ok = client.callTool("echo", Map.of("v", "42"), SHORT).get(2, TimeUnit.SECONDS);
        assertEquals("ok:42", ok.text());
        assertFalse(ok.isError());

        McpCallResult err = client.callTool("boom", Map.of(), SHORT).get(2, TimeUnit.SECONDS);
        assertTrue(err.isError());
    }

    @Test
    void callFlattensMultiPartContentAndSummarizesNonText() throws Exception {
        var content = List.<Map<String, Object>>of(Map.of("type", "text", "text", "one"),
                                        Map.of("type", "text", "text", "two"), Map.of("type", "image", "data", "..."));
        var transport = new FakeMcpTransport(List.of(List.of()),
                                        (t, a) -> FakeMcpTransport.Outcome.rawContent(content, false));
        var client = connected(transport);

        McpCallResult r = client.callTool("m", Map.of(), SHORT).get(2, TimeUnit.SECONDS);
        assertEquals("one\ntwo\n[image omitted]", r.text());
        assertFalse(r.isError());
    }

    @Test
    void callWithEmptyOrMissingContentYieldsEmptyText() throws Exception {
        var transport = new FakeMcpTransport(List.of(List.of()),
                                        (t, a) -> FakeMcpTransport.Outcome.rawContent(List.of(), false));
        var client = connected(transport);
        assertEquals("", client.callTool("m", Map.of(), SHORT).get(2, TimeUnit.SECONDS).text());
    }

    @Test
    void callTimesOutWhenServerNeverResponds() throws Exception {
        var transport = new FakeMcpTransport(List.of(List.of()), (t, a) -> FakeMcpTransport.Outcome.hang());
        var client = connected(transport);

        CompletableFuture<McpCallResult> future = client.callTool("hang", Map.of(), Duration.ofMillis(150));
        var ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, ex.getCause());
    }

    @Test
    void inFlightCallsFailWhenTheServerCrashes() throws Exception {
        var transport = new FakeMcpTransport(List.of(List.of()), (t, a) -> FakeMcpTransport.Outcome.hang());
        var client = connected(transport);

        // generous timeout so it's the crash (not the timeout) that fails the call
        CompletableFuture<McpCallResult> future = client.callTool("hang", Map.of(), Duration.ofSeconds(30));
        transport.close(); // simulate the server exiting

        var ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertNotNull(ex.getCause());
        assertFalse(client.alive());
    }

    @Test
    void listToolsHandlesSinglePageAndSkipsNamelessEntries() throws Exception {
        var page = List.<Map<String, Object>>of(FakeMcpTransport.tool("real", "ok"),
                                        Map.of("description", "no name", "inputSchema", Map.of("type", "object")));
        var transport = new FakeMcpTransport(List.of(page), (t, a) -> FakeMcpTransport.Outcome.result("x"));
        var client = connected(transport);

        var tools = client.listTools(SHORT);
        assertEquals(List.of("real"), tools.stream().map(McpToolDescriptor::name).toList());
    }

    @Test
    void callWithNullArgumentsSendsEmptyObject() throws Exception {
        var transport = new FakeMcpTransport(List.of(List.of()),
                                        (t, a) -> FakeMcpTransport.Outcome.result("args=" + a.toString()));
        var client = connected(transport);

        McpCallResult result = client.callTool("echo", null, SHORT).get(2, TimeUnit.SECONDS);
        assertEquals("args={}", result.text());
        assertTrue(transport.sent().stream().anyMatch(l -> l.contains("\"arguments\":{}")),
                                        transport.sent().toString());
    }

    @Test
    void repliesMethodNotFoundToServerInitiatedRequest() throws Exception {
        var transport = new FakeMcpTransport(List.of(List.of()), (t, a) -> FakeMcpTransport.Outcome.result("x"));
        connected(transport);

        transport.inject("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"sampling/createMessage\",\"params\":{}}");

        Await.until(() -> transport.sent().stream().anyMatch(l -> l.contains("\"id\":99") && l.contains("-32601")));
        String reply = transport.sent().stream().filter(l -> l.contains("\"id\":99")).reduce("", (a, b) -> b);
        assertTrue(reply.contains("-32601"), reply);
        assertFalse(reply.contains("\"method\""), "a reply must not carry a method: " + reply);
    }

    @Test
    void ignoresNotificationsAndUnknownIdResponsesThenKeepsWorking() throws Exception {
        var transport = new FakeMcpTransport(List.of(List.of()), (t, a) -> FakeMcpTransport.Outcome.result("still ok"));
        var client = connected(transport);

        transport.inject("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}");
        transport.inject("{\"jsonrpc\":\"2.0\",\"id\":777777,\"result\":{}}"); // unknown id

        McpCallResult result = client.callTool("echo", Map.of(), SHORT).get(2, TimeUnit.SECONDS);
        assertEquals("still ok", result.text());
        assertTrue(client.alive());
    }

    @Test
    void errorResponseWithoutMessageStillFailsTheCall() throws Exception {
        var transport = new FakeMcpTransport(List.of(List.of()), (t, a) -> FakeMcpTransport.Outcome.protocolError());
        var client = connected(transport);

        var ex = assertThrows(ExecutionException.class,
                                        () -> client.callTool("boom", Map.of(), SHORT).get(2, TimeUnit.SECONDS));
        assertInstanceOf(McpClient.McpException.class, ex.getCause());
        assertNotNull(ex.getCause().getMessage());
    }

    @Test
    void closeIsIdempotentAndFailsPendingCalls() throws Exception {
        var transport = new FakeMcpTransport(List.of(List.of()), (t, a) -> FakeMcpTransport.Outcome.hang());
        var client = connected(transport);

        var future = client.callTool("hang", Map.of(), Duration.ofSeconds(30));
        client.close();
        assertDoesNotThrow(client::close); // idempotent

        assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertFalse(client.alive());
    }

}
