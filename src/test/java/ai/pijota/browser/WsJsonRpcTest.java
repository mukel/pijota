package ai.pijota.browser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The id→caller routing of the shared WebSocket JSON-RPC transport — exercised without a real socket. */
class WsJsonRpcTest {

    private WsJsonRpc rpc() {
        return new WsJsonRpc(HttpClient.newHttpClient(), 1000, 1000);
    }

    @Test
    void resolvesResultToTheWaitingCaller() throws Exception {
        var rpc = rpc();
        CompletableFuture<JsonNode> fut = rpc.awaitId(7);
        rpc.handleMessage("{\"id\":7,\"result\":{\"result\":{\"value\":\"hello\"}}}");
        JsonNode result = fut.get(1, TimeUnit.SECONDS);
        assertEquals("hello", result.path("result").path("value").asText());
    }

    @Test
    void surfacesErrorsAsExceptions() {
        var rpc = rpc();
        CompletableFuture<JsonNode> fut = rpc.awaitId(8);
        rpc.handleMessage("{\"id\":8,\"error\":{\"code\":-32000,\"message\":\"boom\"}}");
        var ex = assertThrows(ExecutionException.class, () -> fut.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause().getMessage().contains("boom"));
    }

    @Test
    void ignoresFramesWithoutAnId() {
        var rpc = rpc();
        CompletableFuture<JsonNode> fut = rpc.awaitId(9);
        rpc.handleMessage("{\"method\":\"Page.loadEventFired\",\"params\":{}}");
        assertFalse(fut.isDone(), "an unsolicited event must not complete a pending request");
    }

    @Test
    void malformedFrameDoesNotThrow() {
        assertDoesNotThrow(() -> rpc().handleMessage("not json"));
    }
}
