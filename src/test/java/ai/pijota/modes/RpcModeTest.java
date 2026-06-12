package ai.pijota.modes;

import ai.pijota.messages.Message;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RpcModeTest {

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
}
