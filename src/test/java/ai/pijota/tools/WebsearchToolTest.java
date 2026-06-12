package ai.pijota.tools;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebsearchToolTest {

    @Test
    void nameIsWebsearch() {
        var tool = new WebsearchTool();
        assertEquals("websearch", tool.name());
    }

    @Test
    void defaultEnabledIsTrue() {
        assertTrue(new WebsearchTool().defaultEnabled());
    }

    @Test
    void stripHtmlRemovesTags() {
        var result = WebsearchTool.stripHtml("<b>bold</b> <i>italic</i>");
        assertTrue(result.contains("bold"));
        assertTrue(result.contains("italic"));
        assertFalse(result.contains("<b>"));
    }

    @Test
    void stripHtmlDecodesEntities() {
        var result = WebsearchTool.stripHtml("&amp; &lt; &gt;");
        assertTrue(result.contains("&"));
        assertTrue(result.contains("<"));
        assertTrue(result.contains(">"));
        assertFalse(result.contains("&amp;"));
    }

    @Test
    void stripHtmlEmptyString() {
        assertEquals("", WebsearchTool.stripHtml(""));
    }

    @Test
    void stripHtmlHandlesNewlinesAndMultipleTags() {
        var html = "<div><p>line1</p><p>line2</p></div>";
        var text = WebsearchTool.stripHtml(html);
        assertTrue(text.contains("line1"));
        assertTrue(text.contains("line2"));
    }

    @Test
    void cleanUrlAddsHttpsForProtocolRelative() {
        assertEquals("https://example.com", WebsearchTool.cleanUrl("//example.com"));
    }

    @Test
    void cleanUrlReturnsEmptyForRootRelative() {
        assertEquals("", WebsearchTool.cleanUrl("/path/to/page"));
    }

    @Test
    void cleanUrlPassesThroughAbsolute() {
        assertEquals("https://example.com", WebsearchTool.cleanUrl("https://example.com"));
    }

    @Test
    void executeBlankQueryReturnsError() throws Exception {
        var tool = new WebsearchTool();
        var result = tool.execute("call-1", Map.of("query", "  "), "/cwd").get();
        assertTrue(result.isError());
    }

    @Test
    void executeWithNullQueryReturnsError() throws Exception {
        var tool = new WebsearchTool();
        var result = tool.execute("call-1", Map.of(), "/cwd").get();
        assertTrue(result.isError());
    }

    @Test
    void executeWithWhitespaceQueryReturnsError() throws Exception {
        var tool = new WebsearchTool();
        var result = tool.execute("call-1", Map.of("query", "   "), "/cwd").get();
        assertTrue(result.isError());
    }

    @Test
    void descriptionIsNotEmpty() {
        var tool = new WebsearchTool();
        assertNotNull(tool.description());
        assertFalse(tool.description().isBlank());
    }

    @Test
    void schemaHasQueryRequired() {
        var tool = new WebsearchTool();
        assertTrue(tool.schema().get("required") instanceof java.util.List);
    }
}
