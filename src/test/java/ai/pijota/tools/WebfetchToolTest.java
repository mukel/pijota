package ai.pijota.tools;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import ai.pijota.testkit.FakeHttpClient;

import java.util.Map;

import static ai.pijota.testkit.ToolRunner.run;
import static org.junit.jupiter.api.Assertions.*;

class WebfetchToolTest {

    @Test
    void nameIsWebfetch() {
        var tool = new WebfetchTool();
        assertEquals("webfetch", tool.name());
    }

    @Test
    void defaultEnabledIsTrue() {
        assertTrue(new WebfetchTool().defaultEnabled());
    }

    @Test
    void acceptHeaderMarkdown() {
        var header = WebfetchTool.acceptHeader("markdown");
        assertTrue(header.contains("text/markdown"));
    }

    @Test
    void acceptHeaderText() {
        var header = WebfetchTool.acceptHeader("text");
        assertTrue(header.contains("text/plain"));
        assertFalse(header.contains("markdown"));
    }

    @Test
    void acceptHeaderHtml() {
        var header = WebfetchTool.acceptHeader("html");
        assertTrue(header.contains("text/html"));
    }

    @Test
    void acceptHeaderDefault() {
        var header = WebfetchTool.acceptHeader("unknown");
        assertTrue(header.contains("text/html"));
    }

    @Test
    void htmlToMarkdownConvertsBasicTags() {
        var html = "<p>Hello</p><p>World</p>";
        var md = WebfetchTool.htmlToMarkdown(html);
        assertTrue(md.contains("Hello"));
        assertTrue(md.contains("World"));
        assertFalse(md.contains("<p>"));
    }

    @Test
    void htmlToMarkdownStripsScripts() {
        var html = "<script>alert(1)</script>text";
        var md = WebfetchTool.htmlToMarkdown(html);
        assertFalse(md.contains("alert"));
        assertTrue(md.contains("text"));
    }

    @Test
    void htmlToMarkdownStripsStyles() {
        var html = "<style>body{color:red}</style>content";
        var md = WebfetchTool.htmlToMarkdown(html);
        assertFalse(md.contains("color"));
        assertTrue(md.contains("content"));
    }

    @Test
    void htmlToMarkdownConvertsLinks() {
        var html = "<a href=\"https://example.com\">click</a>";
        var md = WebfetchTool.htmlToMarkdown(html);
        assertTrue(md.contains("click") || md.contains("example"));
    }

    @Test
    void htmlToMarkdownConvertsImageAndCode() {
        var html = "<img src=\"pic.png\" alt=\"pic\"><code>code</code>";
        var md = WebfetchTool.htmlToMarkdown(html);
        assertTrue(md.contains("pic.png"));
        assertTrue(md.contains("`code`"));
    }

    @Test
    void htmlToMarkdownConvertsBold() {
        var html = "<b>bold</b>";
        var md = WebfetchTool.htmlToMarkdown(html);
        assertTrue(md.contains("**bold**") || md.contains("bold"));
    }

    @Test
    void htmlToMarkdownDecodesEntities() {
        var html = "&amp; &lt; &gt;";
        var md = WebfetchTool.htmlToMarkdown(html);
        assertTrue(md.contains("&"));
        assertTrue(md.contains("<"));
        assertTrue(md.contains(">"));
    }

    @Test
    void htmlToTextConvertsBreaks() {
        var html = "line1<br>line2";
        var text = WebfetchTool.htmlToText(html);
        assertTrue(text.contains("\n"));
    }

    @Test
    void htmlToTextStripsTags() {
        var html = "<div>text</div>";
        var text = WebfetchTool.htmlToText(html);
        assertFalse(text.contains("<div"));
        assertTrue(text.contains("text"));
    }

    @Test
    void htmlToTextStripsScriptsAndDecodesEntities() {
        var html = "<script>x</script><div>a&nbsp;b</div>";
        var text = WebfetchTool.htmlToText(html);
        assertFalse(text.contains("script"));
        assertTrue(text.contains("a b"));
    }

    @Test
    void convertContentMarkdownFormatWithHtmlMime() {
        var content = "<p>Hello</p>";
        var result = WebfetchTool.convertContent(content, "text/html", "markdown");
        assertFalse(result.contains("<p>"));
        assertTrue(result.contains("Hello"));
    }

    @Test
    void convertContentTextFormatWithHtmlMime() {
        var content = "<p>Hello</p>";
        var result = WebfetchTool.convertContent(content, "text/html", "text");
        assertFalse(result.contains("<p>"));
        assertTrue(result.contains("Hello"));
    }

    @Test
    void convertContentHtmlFormatPassesThrough() {
        var content = "<p>raw</p>";
        var result = WebfetchTool.convertContent(content, "text/html", "html");
        assertTrue(result.contains("<p>"));
    }

    @Test
    void convertContentPlainTextPassesThrough() {
        var content = "plain text";
        var result = WebfetchTool.convertContent(content, "text/plain", "markdown");
        assertEquals("plain text", result);
    }

    @Test
    void convertContentDefaultFormat() {
        var content = "<p>Hello</p>";
        var result = WebfetchTool.convertContent(content, "text/html", "unknown");
        assertFalse(result.contains("<p>"));
        assertTrue(result.contains("Hello"));
    }

    @Test
    void detectCharsetFromContentType() {
        var charset = WebfetchTool.detectCharset("text/html; charset=ISO-8859-1", new byte[0]);
        assertEquals("ISO-8859-1", charset);
    }

    @Test
    void detectCharsetDefaultUtf8() {
        var charset = WebfetchTool.detectCharset("text/html", new byte[0]);
        assertEquals(StandardCharsets.UTF_8.name(), charset);
    }

    @Test
    void detectCharsetWithBom() {
        var body = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 't', 'e', 'x', 't'};
        var charset = WebfetchTool.detectCharset("text/plain", body);
        assertEquals(StandardCharsets.UTF_8.name(), charset);
    }

    @Test
    void detectCharsetInvalidFallsBackToUtf8() {
        var charset = WebfetchTool.detectCharset("text/html; charset=invalid-charset", new byte[0]);
        assertEquals(StandardCharsets.UTF_8.name(), charset);
    }

    @Test
    void executeWithNullUrlReturnsError() {
        var tool = new WebfetchTool();
        var result = run(tool, Map.of(), "/cwd");
        assertTrue(result.isError());
    }

    @Test
    void executeWithBlankUrlReturnsError() {
        var tool = new WebfetchTool();
        var result = run(tool, Map.of("url", "  "), "/cwd");
        assertTrue(result.isError());
    }

    @Test
    void executeWithInvalidUrlReturnsError() {
        var tool = new WebfetchTool();
        var result = run(tool, Map.of("url", "not a url"), "/cwd");
        assertTrue(result.isError());
    }

    @Test
    void internalAddressesAreBlockedForSsrf() {
        // loopback, link-local (incl. 169.254.169.254 cloud metadata), private, wildcard — must be refused.
        for (String h : new String[] {"127.0.0.1", "localhost", "169.254.169.254", "10.0.0.1", "192.168.1.1", "0.0.0.0",
                "::1"})
            assertTrue(WebfetchTool.isInternalAddress(h), h + " should be blocked");
    }

    @Test
    void publicAddressesAreAllowed() {
        assertFalse(WebfetchTool.isInternalAddress("8.8.8.8"));
    }

    @Test
    void executeRefusesInternalUrl() {
        var tool = new WebfetchTool();
        var result = run(tool, Map.of("url", "http://169.254.169.254/latest/meta-data/"), "/cwd");
        assertTrue(result.isError());
        assertTrue(result.content().toLowerCase().contains("internal"), result.content());
    }

    @Test
    void fetchesPageContentThroughTheInjectedClient() {
        byte[] html = "<html><body><h1>Title</h1><p>Hello world</p></body></html>".getBytes(StandardCharsets.UTF_8);
        var tool = new WebfetchTool(FakeHttpClient.ok(html)); // canned page, no network

        var result = run(tool, Map.of("url", "example.com", "format", "text"), "/cwd");

        assertFalse(result.isError(), result.content());
        assertTrue(result.content().contains("Hello world"), result.content());
    }

    @Test
    void surfacesHttpErrorStatuses() {
        var tool = new WebfetchTool(FakeHttpClient.status(404, "nope".getBytes(StandardCharsets.UTF_8)));
        var result = run(tool, Map.of("url", "example.com"), "/cwd");
        assertTrue(result.isError());
        assertTrue(result.content().contains("404"), result.content());
    }
}
