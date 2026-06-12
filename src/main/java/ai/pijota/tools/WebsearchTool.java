package ai.pijota.tools;

import ai.pijota.util.Util;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public final class WebsearchTool implements Tool {
    private static final int DEFAULT_NUM_RESULTS = 8;
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                    + " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                                    .connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public String name() {
        return "websearch";
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    @Override
    public String description() {
        return "Search the web using real-time web searches and scrape content from specific URLs. "
                                        + "Provides up-to-date information for current events and recent data. "
                                        + "Supports configurable result counts. "
                                        + "Use this tool for accessing information beyond knowledge cutoff.\n\n"
                                        + "Usage notes:\n" + "- Search results include titles, URLs, and snippets\n"
                                        + "- Configure numResults for more/fewer results (default 8)\n"
                                        + "- Results may be summarized if very large";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("type", "object", "properties", Map.of("query",
                                        Map.of("type", "string", "description", "Websearch query"), "numResults",
                                        Map.of("type", "integer", "description",
                                                                        "Number of search results to return (default: 8)")),
                                        "required", List.of("query"), "additionalProperties", false);
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Map<String, Object> args, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String query = (String) args.get("query");
                if (query == null || query.isBlank())
                    return ToolResult.error("No query provided");

                int numResults = Math.max(1, Util.getInt(args, "numResults", DEFAULT_NUM_RESULTS));

                String provider = System.getenv().getOrDefault("OPENCODE_WEBSEARCH_PROVIDER", "ddg");
                List<SearchResult> results = switch (provider) {
                    case "exa" -> exaSearch(query, numResults);
                    case "parallel" -> parallelSearch(query, numResults);
                    default -> ddgSearch(query, numResults);
                };

                if (results.isEmpty())
                    return ToolResult.success("No search results found for: " + query);

                var sb = new StringBuilder();
                for (int i = 0; i < results.size(); i++) {
                    var r = results.get(i);
                    sb.append(i + 1).append(". **").append(r.title()).append("**\n");
                    sb.append("   URL: ").append(r.url()).append('\n');
                    sb.append("   ").append(r.snippet()).append("\n\n");
                }

                String output = sb.toString().stripTrailing();
                if (output.length() > Util.DEFAULT_MAX_CHARS) {
                    var truncated = Util.truncateToFile(output, Util.DEFAULT_MAX_CHARS, "pijota-websearch-");
                    return ToolResult.success(truncated.text(), truncated.details());
                }
                return ToolResult.success(output);
            } catch (Exception e) {
                return ToolResult.error("Search error: " + e.getMessage());
            }
        });
    }

    private List<SearchResult> ddgSearch(String query, int numResults) throws Exception {
        String url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA)
                                        .timeout(Duration.ofSeconds(15)).GET().build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String html = response.body();

        var results = new ArrayList<SearchResult>();
        var resultBlockPattern = Pattern.compile(
                                        "<a[^>]*class\\s*=\\s*['\"]result__a['\"][^>]*href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>\\s*(.*?)\\s*</a>",
                                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        var snippetPattern = Pattern.compile("<a[^>]*class\\s*=\\s*['\"]result__snippet['\"][^>]*>(.*?)</a>",
                                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        var matcher = resultBlockPattern.matcher(html);
        while (matcher.find() && results.size() < numResults) {
            String rawUrl = matcher.group(1);
            String title = stripHtml(matcher.group(2)).strip();
            String snippet = "";
            var snippetMatcher = snippetPattern.matcher(html.substring(matcher.end()));
            if (snippetMatcher.find()) {
                snippet = stripHtml(snippetMatcher.group(1)).strip();
            }
            if (!title.isBlank()) {
                results.add(new SearchResult(title, cleanUrl(rawUrl), snippet));
            }
        }

        return results;
    }

    private List<SearchResult> exaSearch(String query, int numResults) throws Exception {
        String key = System.getenv("EXA_API_KEY");
        if (key == null || key.isBlank())
            return ddgSearch(query, numResults);

        String body = String.format("""
                                        {"query":"%s","numResults":%d,"type":"auto","contents":{"text":true}}""",
                                        query.replace("\\", "\\\\").replace("\"", "\\\""), numResults);

        HttpURLConnection conn = (HttpURLConnection) URI.create("https://api.exa.ai/search").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", key);
        conn.setRequestProperty("User-Agent", UA);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(25_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        try {
            String response = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (code != 200)
                return ddgSearch(query, numResults);

            JsonNode root = Util.MAPPER.readTree(response);
            var results = new ArrayList<SearchResult>();
            for (JsonNode item : root.path("results")) {
                results.add(new SearchResult(item.path("title").asText(""), item.path("url").asText(""),
                                                item.path("text").asText("")));
            }
            return results;
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {}
            conn.disconnect();
        }
    }

    private List<SearchResult> parallelSearch(String query, int numResults) throws Exception {
        String key = System.getenv("PARALLEL_API_KEY");
        if (key == null || key.isBlank())
            return ddgSearch(query, numResults);

        String body = String.format("""
                                        {"objective":"%s","search_queries":["%s"]}""",
                                        query.replace("\\", "\\\\").replace("\"", "\\\""),
                                        query.replace("\\", "\\\\").replace("\"", "\\\""));

        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.parallel.ai/api/v1/web_search"))
                                        .header("Content-Type", "application/json")
                                        .header("Authorization", "Bearer " + key).header("User-Agent", "opencode")
                                        .timeout(Duration.ofSeconds(25)).POST(HttpRequest.BodyPublishers.ofString(body))
                                        .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            return ddgSearch(query, numResults);

        JsonNode root = Util.MAPPER.readTree(response.body());
        var results = new ArrayList<SearchResult>();
        for (JsonNode item : root.path("results")) {
            results.add(new SearchResult(item.path("title").asText(""), item.path("url").asText(""),
                                            item.path("content").asText(item.path("snippet").asText(""))));
        }
        return results;
    }

    static String stripHtml(String html) {
        return html.replaceAll("(?is)<[^>]+>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                                        .replace("&quot;", "\"").replace("&nbsp;", " ").replace("&#39;", "'");
    }

    static String cleanUrl(String url) {
        if (url.startsWith("//"))
            return "https:" + url;
        if (url.startsWith("/"))
            return "";
        return url;
    }

    private record SearchResult(String title, String url, String snippet) {
    }
}
