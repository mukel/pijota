package ai.pijota.tools;

import static ai.pijota.testkit.ToolRunner.run;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import ai.pijota.testkit.FakeHttpClient;
import ai.pijota.tools.WebsearchTool.SearchResult;

class WebsearchToolTest {

    // ─── metadata ───

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
    void glyphIsGlobe() {
        assertEquals("🌐", new WebsearchTool().glyph());
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

    // ─── stripHtml ───

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
    void stripHtmlDecodesAllEntities() {
        var result = WebsearchTool.stripHtml("&quot;quoted&quot; &nbsp;space&#39;apostrophe");
        assertTrue(result.contains("\"quoted\""));
        assertTrue(result.contains(" space"));
        assertTrue(result.contains("apostrophe"));
    }

    @Test
    void stripHtmlPreservesPlainText() {
        assertEquals("hello world", WebsearchTool.stripHtml("hello world"));
    }

    @Test
    void stripHtmlHandlesNestedTags() {
        assertEquals("inner", WebsearchTool.stripHtml("<a><b><c>inner</c></b></a>"));
    }

    // ─── cleanUrl ───

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
    void cleanUrlPassesThroughHttpAbsolute() {
        assertEquals("http://example.com", WebsearchTool.cleanUrl("http://example.com"));
    }

    @Test
    void cleanUrlHandlesProtocolRelativeWithPath() {
        assertEquals("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com",
                                        WebsearchTool.cleanUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com"));
    }

    // ─── parseDdgResults ───

    private static final String DDG_HTML = """
                                    <div class="results">
                                      <a class="result__a" href="https://example.com/1">First Result</a>
                                      <a class="result__snippet">First snippet text</a>
                                      <a class="result__a" href="https://example.com/2">Second Result</a>
                                      <a class="result__snippet">Second snippet text</a>
                                      <a class="result__a" href="https://example.com/3">Third Result</a>
                                      <a class="result__snippet">Third snippet text</a>
                                    </div>""";

    @Test
    void parseDdgResultsExtractsAllResults() {
        var results = WebsearchTool.parseDdgResults(DDG_HTML, 10);
        assertEquals(3, results.size());
        assertEquals("First Result", results.get(0).title());
        assertEquals("https://example.com/1", results.get(0).url());
        assertEquals("First snippet text", results.get(0).snippet());
        assertEquals("Second Result", results.get(1).title());
        assertEquals("Third Result", results.get(2).title());
    }

    @Test
    void parseDdgResultsRespectsNumResultsLimit() {
        var results = WebsearchTool.parseDdgResults(DDG_HTML, 2);
        assertEquals(2, results.size());
        assertEquals("First Result", results.get(0).title());
        assertEquals("Second Result", results.get(1).title());
    }

    @Test
    void parseDdgResultsFromEmptyHtml() {
        var results = WebsearchTool.parseDdgResults("", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void parseDdgResultsFromHtmlWithNoResultLinks() {
        var html = "<div><p>No results here</p></div>";
        var results = WebsearchTool.parseDdgResults(html, 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void parseDdgResultsExtractsSnippets() {
        var results = WebsearchTool.parseDdgResults(DDG_HTML, 10);
        assertEquals("First snippet text", results.get(0).snippet());
        assertEquals("Second snippet text", results.get(1).snippet());
    }

    @Test
    void parseDdgResultsStripsHtmlFromTitles() {
        var html = """
                                        <a class="result__a" href="https://example.com"><b>Bold</b> Title</a>
                                        <a class="result__snippet">Snippet</a>""";
        var results = WebsearchTool.parseDdgResults(html, 10);
        assertEquals(1, results.size());
        assertTrue(results.get(0).title().contains("Bold"));
        assertTrue(results.get(0).title().contains("Title"));
        assertFalse(results.get(0).title().contains("<b>"));
    }

    @Test
    void parseDdgResultsStripsHtmlFromSnippets() {
        var html = """
                                        <a class="result__a" href="https://example.com">Title</a>
                                        <a class="result__snippet">Snippet with <em>emphasis</em></a>""";
        var results = WebsearchTool.parseDdgResults(html, 10);
        assertEquals(1, results.size());
        assertTrue(results.get(0).snippet().contains("emphasis"));
        assertFalse(results.get(0).snippet().contains("<em>"));
    }

    @Test
    void parseDdgResultsSkipsBlankTitles() {
        var html = """
                                        <a class="result__a" href="https://example.com/1">   </a>
                                        <a class="result__snippet">Snippet</a>
                                        <a class="result__a" href="https://example.com/2">Real Title</a>
                                        <a class="result__snippet">Real snippet</a>""";
        var results = WebsearchTool.parseDdgResults(html, 10);
        assertEquals(1, results.size());
        assertEquals("Real Title", results.get(0).title());
    }

    @Test
    void parseDdgResultsHandlesProtocolRelativeUrls() {
        var html = """
                                        <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com">Title</a>
                                        <a class="result__snippet">Snippet</a>""";
        var results = WebsearchTool.parseDdgResults(html, 10);
        assertEquals(1, results.size());
        assertTrue(results.get(0).url().startsWith("https://duckduckgo.com/"));
    }

    @Test
    void parseDdgResultsHandlesRootRelativeUrls() {
        var html = """
                                        <a class="result__a" href="/path/to/page">Title</a>
                                        <a class="result__snippet">Snippet</a>""";
        var results = WebsearchTool.parseDdgResults(html, 10);
        assertEquals(1, results.size());
        assertEquals("", results.get(0).url());
    }

    @Test
    void parseDdgResultsDecodesEntitiesInTitle() {
        var html = """
                                        <a class="result__a" href="https://example.com">Tom &amp; Jerry</a>
                                        <a class="result__snippet">Snippet</a>""";
        var results = WebsearchTool.parseDdgResults(html, 10);
        assertEquals(1, results.size());
        assertEquals("Tom & Jerry", results.get(0).title());
    }

    @Test
    void parseDdgResultsWithMissingSnippetHasEmptySnippet() {
        var html = """
                                        <a class="result__a" href="https://example.com/1">First</a>
                                        <a class="result__snippet">First snippet</a>
                                        <a class="result__a" href="https://example.com/2">Last No Snippet</a>""";
        var results = WebsearchTool.parseDdgResults(html, 10);
        assertEquals(2, results.size());
        assertEquals("First snippet", results.get(0).snippet());
        assertEquals("", results.get(1).snippet());
    }

    // ─── parseExaResults ───

    @Test
    void parseExaResultsExtractsResults() throws Exception {
        var json = """
                                        {"results":[
                                          {"title":"Exa Result","url":"https://exa.com","text":"Exa content"},
                                          {"title":"Second","url":"https://second.com","text":"Second content"}
                                        ]}""";
        var results = WebsearchTool.parseExaResults(json);
        assertEquals(2, results.size());
        assertEquals("Exa Result", results.get(0).title());
        assertEquals("https://exa.com", results.get(0).url());
        assertEquals("Exa content", results.get(0).snippet());
    }

    @Test
    void parseExaResultsEmptyArray() throws Exception {
        var json = "{\"results\":[]}";
        var results = WebsearchTool.parseExaResults(json);
        assertTrue(results.isEmpty());
    }

    @Test
    void parseExaResultsMissingFieldsDefaultToEmpty() throws Exception {
        var json = """
                                        {"results":[{"title":"Only Title"}]}""";
        var results = WebsearchTool.parseExaResults(json);
        assertEquals(1, results.size());
        assertEquals("Only Title", results.get(0).title());
        assertEquals("", results.get(0).url());
        assertEquals("", results.get(0).snippet());
    }

    @Test
    void parseExaResultsMissingResultsKey() throws Exception {
        var json = "{\"error\":\"bad request\"}";
        var results = WebsearchTool.parseExaResults(json);
        assertTrue(results.isEmpty());
    }

    // ─── parseParallelResults ───

    @Test
    void parseParallelResultsExtractsWithContent() throws Exception {
        var json = """
                                        {"results":[
                                          {"title":"Parallel Result","url":"https://parallel.com","content":"Parallel content"}
                                        ]}""";
        var results = WebsearchTool.parseParallelResults(json);
        assertEquals(1, results.size());
        assertEquals("Parallel Result", results.get(0).title());
        assertEquals("https://parallel.com", results.get(0).url());
        assertEquals("Parallel content", results.get(0).snippet());
    }

    @Test
    void parseParallelResultsFallsBackToSnippet() throws Exception {
        var json = """
                                        {"results":[
                                          {"title":"Title","url":"https://example.com","snippet":"Snippet fallback"}
                                        ]}""";
        var results = WebsearchTool.parseParallelResults(json);
        assertEquals(1, results.size());
        assertEquals("Snippet fallback", results.get(0).snippet());
    }

    @Test
    void parseParallelResultsContentTakesPriorityOverSnippet() throws Exception {
        var json = """
                                        {"results":[
                                          {"title":"Title","url":"https://example.com","content":"content","snippet":"snippet"}
                                        ]}""";
        var results = WebsearchTool.parseParallelResults(json);
        assertEquals(1, results.size());
        assertEquals("content", results.get(0).snippet());
    }

    @Test
    void parseParallelResultsEmptyArray() throws Exception {
        var json = "{\"results\":[]}";
        var results = WebsearchTool.parseParallelResults(json);
        assertTrue(results.isEmpty());
    }

    @Test
    void parseParallelResultsMissingFieldsDefaultToEmpty() throws Exception {
        var json = """
                                        {"results":[{"title":"Only Title"}]}""";
        var results = WebsearchTool.parseParallelResults(json);
        assertEquals(1, results.size());
        assertEquals("Only Title", results.get(0).title());
        assertEquals("", results.get(0).url());
        assertEquals("", results.get(0).snippet());
    }

    // ─── formatResults ───

    @Test
    void formatResultsSingleResult() {
        var results = List.of(new SearchResult("Title", "https://example.com", "Snippet"));
        var output = WebsearchTool.formatResults(results);
        assertTrue(output.contains("1. **Title**"));
        assertTrue(output.contains("URL: https://example.com"));
        assertTrue(output.contains("Snippet"));
    }

    @Test
    void formatResultsMultipleResults() {
        var results = List.of(new SearchResult("First", "https://a.com", "Snip A"),
                                        new SearchResult("Second", "https://b.com", "Snip B"));
        var output = WebsearchTool.formatResults(results);
        assertTrue(output.contains("1. **First**"));
        assertTrue(output.contains("2. **Second**"));
        assertTrue(output.contains("URL: https://a.com"));
        assertTrue(output.contains("URL: https://b.com"));
    }

    @Test
    void formatResultsEmptyList() {
        var output = WebsearchTool.formatResults(List.of());
        assertEquals("", output);
    }

    @Test
    void formatResultsStripsTrailingWhitespace() {
        var results = List.of(new SearchResult("Title", "https://example.com", "Snippet"));
        var output = WebsearchTool.formatResults(results);
        assertFalse(output.endsWith("\n"));
    }

    // ─── buildResult ───

    @Test
    void buildResultEmptyReturnsNoResultsMessage() throws Exception {
        var result = WebsearchTool.buildResult(List.of(), "test query");
        assertFalse(result.isError());
        assertTrue(result.content().contains("No search results found"));
        assertTrue(result.content().contains("test query"));
    }

    @Test
    void buildResultNormalReturnsFormattedOutput() throws Exception {
        var results = List.of(new SearchResult("Title", "https://example.com", "Snippet"));
        var result = WebsearchTool.buildResult(results, "query");
        assertFalse(result.isError());
        assertTrue(result.content().contains("1. **Title**"));
        assertTrue(result.content().contains("https://example.com"));
    }

    @Test
    void buildResultTruncatesLargeOutput() throws Exception {
        var big = "x".repeat(60_000);
        var results = List.of(new SearchResult("T1", "https://a.com", big),
                                        new SearchResult("T2", "https://b.com", big));
        var result = WebsearchTool.buildResult(results, "query");
        assertFalse(result.isError());
        assertTrue(result.content().length() < big.length() * 2);
        assertEquals(true, result.details().get("truncated"));
        assertNotNull(result.details().get("fullOutputPath"));
    }

    // ─── execute() integration via test subclass ───

    private static WebsearchTool toolWithResults(List<SearchResult> results) {
        return new WebsearchTool() {
            @Override
            List<SearchResult> ddgSearch(String query, int numResults) {
                return results;
            }
        };
    }

    private static WebsearchTool toolThatThrows(RuntimeException error) {
        return new WebsearchTool() {
            @Override
            List<SearchResult> ddgSearch(String query, int numResults) {
                throw error;
            }
        };
    }

    @Test
    void executeBlankQueryReturnsError() {
        var tool = new WebsearchTool();
        var result = run(tool, Map.of("query", "  "), "/cwd");
        assertTrue(result.isError());
    }

    @Test
    void executeWithNullQueryReturnsError() {
        var tool = new WebsearchTool();
        var result = run(tool, Map.of(), "/cwd");
        assertTrue(result.isError());
    }

    @Test
    void executeReturnsFormattedResults() {
        var results = List.of(new SearchResult("Test Result", "https://example.com", "A snippet"));
        var tool = toolWithResults(results);
        var result = run(tool, Map.of("query", "test"), "/cwd");
        assertFalse(result.isError());
        assertTrue(result.content().contains("Test Result"));
        assertTrue(result.content().contains("https://example.com"));
        assertTrue(result.content().contains("A snippet"));
    }

    @Test
    void executeEmptyResultsReturnsNoResultsMessage() {
        var tool = toolWithResults(List.of());
        var result = run(tool, Map.of("query", "nothing"), "/cwd");
        assertFalse(result.isError());
        assertTrue(result.content().contains("No search results found"));
        assertTrue(result.content().contains("nothing"));
    }

    @Test
    void executeHandlesSearchException() {
        var tool = toolThatThrows(new RuntimeException("connection refused"));
        var result = run(tool, Map.of("query", "test"), "/cwd");
        assertTrue(result.isError());
        assertTrue(result.content().contains("Search error"));
        assertTrue(result.content().contains("connection refused"));
    }

    @Test
    void executeParsesNumResults() {
        var captured = new int[1];
        var tool = new WebsearchTool() {
            @Override
            List<SearchResult> ddgSearch(String query, int numResults) {
                captured[0] = numResults;
                return List.of(new SearchResult("T", "https://e.com", "S"));
            }
        };
        run(tool, Map.of("query", "test", "numResults", 3), "/cwd");
        assertEquals(3, captured[0]);
    }

    @Test
    void executeDefaultsNumResultsTo8() {
        var captured = new int[1];
        var tool = new WebsearchTool() {
            @Override
            List<SearchResult> ddgSearch(String query, int numResults) {
                captured[0] = numResults;
                return List.of(new SearchResult("T", "https://e.com", "S"));
            }
        };
        run(tool, Map.of("query", "test"), "/cwd");
        assertEquals(8, captured[0]);
    }

    @Test
    void executeClampsNumResultsToAtLeast1() {
        var captured = new int[1];
        var tool = new WebsearchTool() {
            @Override
            List<SearchResult> ddgSearch(String query, int numResults) {
                captured[0] = numResults;
                return List.of(new SearchResult("T", "https://e.com", "S"));
            }
        };
        run(tool, Map.of("query", "test", "numResults", 0), "/cwd");
        assertEquals(1, captured[0]);
    }

    @Test
    void executePassesQueryToSearch() {
        var captured = new String[1];
        var tool = new WebsearchTool() {
            @Override
            List<SearchResult> ddgSearch(String query, int numResults) {
                captured[0] = query;
                return List.of(new SearchResult("T", "https://e.com", "S"));
            }
        };
        run(tool, Map.of("query", "java testing"), "/cwd");
        assertEquals("java testing", captured[0]);
    }

    @Test
    void executeWithExaProviderFallsBackToDdg() {
        var results = List.of(new SearchResult("Exa", "https://exa.com", "Exa snippet"));
        var tool = new WebsearchTool() {
            @Override
            List<SearchResult> ddgSearch(String query, int numResults) {
                return results;
            }
        };
        var result = run(tool, Map.of("query", "test"), "/cwd");
        assertFalse(result.isError());
        assertTrue(result.content().contains("Exa"));
    }

    @Test
    void executeWithWhitespaceQueryReturnsError() {
        var tool = new WebsearchTool();
        var result = run(tool, Map.of("query", "   "), "/cwd");
        assertTrue(result.isError());
    }

    // ─── execute() against a canned response (no network) ───

    @Test
    void executeRunsADdgSearchThroughTheInjectedClient() {
        var tool = new WebsearchTool(FakeHttpClient.ok(DDG_HTML)); // canned DDG page, no network
        var result = run(tool, Map.of("query", "anything"), "/cwd");
        assertFalse(result.isError(), result.content());
        assertTrue(result.content().contains("First Result"), result.content());
        assertTrue(result.content().contains("https://example.com/1"), result.content());
    }

    @Test
    void executeRejectsABlankQuery() {
        var result = run(new WebsearchTool(FakeHttpClient.ok("")), Map.of("query", "  "), "/cwd");
        assertTrue(result.isError());
        assertTrue(result.content().contains("No query"));
    }

    // ─── live tests (opt-in via PIJOTA_LIVE=1) ───

    @Test
    @EnabledIfEnvironmentVariable(named = "PIJOTA_LIVE", matches = "1")
    void executeLiveDdgSearch() {
        var tool = new WebsearchTool();
        var result = run(tool, Map.of("query", "java programming language"), "/cwd");
        assertFalse(result.isError());
        assertFalse(result.content().isBlank());
    }
}
