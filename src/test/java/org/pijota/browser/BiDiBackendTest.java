package org.pijota.browser;

import org.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** The pure protocol-translation surface of the WebDriver BiDi backend; no browser/driver needed. */
class BiDiBackendTest {

    private static JsonNode parse(String json) throws Exception {
        return Util.MAPPER.readTree(json);
    }

    private static JsonNode bidi(String remoteValueJson) throws Exception {
        return BiDiBackend.bidiToJson(parse(remoteValueJson));
    }

    @Test
    void unwrapsPrimitives() throws Exception {
        assertEquals("hi", bidi("{\"type\":\"string\",\"value\":\"hi\"}").asText());
        assertTrue(bidi("{\"type\":\"boolean\",\"value\":true}").asBoolean());
        assertEquals(42, bidi("{\"type\":\"number\",\"value\":42}").asInt());
        assertTrue(bidi("{\"type\":\"null\"}").isNull());
        assertTrue(bidi("{\"type\":\"undefined\"}").isNull());
    }

    @Test
    void specialNumbersAndUnknownTypesCollapseToNull() throws Exception {
        assertTrue(bidi("{\"type\":\"number\",\"value\":\"NaN\"}").isNull(), "NaN can't be JSON → null");
        assertTrue(bidi("{\"type\":\"number\",\"value\":\"Infinity\"}").isNull());
        assertTrue(bidi("{\"type\":\"node\"}").isNull(), "DOM nodes aren't returned by the extractors");
        assertTrue(bidi("{\"type\":\"regexp\"}").isNull());
    }

    @Test
    void unwrapsArrays() throws Exception {
        JsonNode arr = bidi("{\"type\":\"array\",\"value\":["
                                        + "{\"type\":\"string\",\"value\":\"a\"},{\"type\":\"number\",\"value\":2}]}");
        assertTrue(arr.isArray());
        assertEquals(2, arr.size());
        assertEquals("a", arr.get(0).asText());
        assertEquals(2, arr.get(1).asInt());
    }

    @Test
    void unwrapsObjectsWithStringKeysRecursively() throws Exception {
        // Mirrors reader.js's return: {title, byline, content}, the shape readerArticle() reads.
        JsonNode obj = bidi("{\"type\":\"object\",\"value\":[" + "[\"title\",{\"type\":\"string\",\"value\":\"T\"}],"
                                        + "[\"byline\",{\"type\":\"null\"}],"
                                        + "[\"content\",{\"type\":\"string\",\"value\":\"<p>x</p>\"}]]}");
        assertTrue(obj.isObject());
        assertEquals("T", obj.path("title").asText());
        assertTrue(obj.path("byline").isNull());
        assertEquals("<p>x</p>", obj.path("content").asText());
    }

    @Test
    void webSocketUrlIsReadFromTheSessionResponse() throws Exception {
        String session = "{\"value\":{\"sessionId\":\"abc\",\"capabilities\":{\"webSocketUrl\":\"ws://127.0.0.1:9/sess\"}}}";
        assertEquals("ws://127.0.0.1:9/sess", BiDiBackend.webSocketUrlFrom(parse(session)));
        assertEquals("", BiDiBackend.webSocketUrlFrom(parse("{\"value\":{\"sessionId\":\"abc\",\"capabilities\":{}}}")),
                                        "no webSocketUrl (driver lacks BiDi) → empty");
    }

    @Test
    void firstContextIsTheRootBrowsingContextId() throws Exception {
        String tree = "{\"contexts\":[{\"context\":\"ctx-1\",\"url\":\"about:blank\",\"children\":[]}]}";
        assertEquals("ctx-1", BiDiBackend.firstContext(parse(tree)));
        assertEquals("", BiDiBackend.firstContext(parse("{\"contexts\":[]}")));
    }

    @Test
    void resolveDriverHonorsTheExplicitOverride() {
        assertEquals(Path.of("/opt/geckodriver"), BiDiBackends.resolveDriver("/opt/geckodriver",
                                        "pijota.browse.geckodriver", "PIJOTA_GECKODRIVER", "geckodriver"));
    }

    @Test
    void firefoxHeadlessModeUsesTheNativeFirefoxArgument() {
        assertTrue(BiDiBackends.firefoxCapabilities(false).path("moz:firefoxOptions").isMissingNode());
        assertEquals("-headless", BiDiBackends.firefoxCapabilities(true).path("moz:firefoxOptions").path("args").get(0)
                                        .asText());
    }

    @Test
    void headlessFirefoxSkipsTheWindowConfirmation() {
        var confirmed = new AtomicBoolean(false);
        var backend = BiDiBackends.firefox(1, "/nonexistent/geckodriver", null, () -> {
            confirmed.set(true);
            return false;
        }, true, 800, 600, List.of());
        assertThrows(Exception.class, backend::start);
        assertFalse(confirmed.get(), "a launch with no window must not ask permission to open one");
    }
}
