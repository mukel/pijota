package ai.pijota.tools;

import ai.pijota.util.Util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public final class WebfetchTool implements Tool {

    private static final int MAX_RESPONSE_SIZE = 5 * 1024 * 1024;
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final long MAX_TIMEOUT_SECONDS = 120;
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                    + " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                                    .connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public String name() {
        return "webfetch";
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    @Override
    public String description() {
        return "Fetches content from a specified URL. Takes a URL and optional format as input. "
                                        + "Fetches the URL content, converts to requested format (markdown by default). "
                                        + "Use this tool when you need to retrieve and analyze web content.\n\n"
                                        + "Usage notes:\n" + "- The URL must be a fully-formed valid URL\n"
                                        + "- HTTP URLs will be automatically upgraded to HTTPS\n"
                                        + "- Format options: \"markdown\" (default), \"text\", or \"html\"\n"
                                        + "- This tool is read-only and does not modify any files\n"
                                        + "- Results may be summarized if the content is very large";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("type", "object", "properties", Map.of("url",
                                        Map.of("type", "string", "description", "The URL to fetch content from"),
                                        "format",
                                        Map.of("type", "string", "description",
                                                                        "The format to return content in (text, markdown, html). Default: markdown"),
                                        "timeout",
                                        Map.of("type", "number", "description",
                                                                        "Optional timeout in seconds (max 120)")),
                                        "required", List.of("url"), "additionalProperties", false);
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Map<String, Object> args, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = (String) args.get("url");
                if (url == null || url.isBlank())
                    return ToolResult.error("No URL provided");

                if (url.startsWith("http://"))
                    url = "https://" + url.substring(7);
                if (!url.startsWith("https://"))
                    url = "https://" + url;

                URI uri;
                try {
                    uri = URI.create(url);
                } catch (Exception e) {
                    return ToolResult.error("Invalid URL: " + url);
                }
                if (uri.getHost() == null)
                    return ToolResult.error("Invalid URL (no host): " + url);

                String format = Util.getString(args, "format", "markdown");
                long timeoutSecs = Util.getLong(args, "timeout", DEFAULT_TIMEOUT_SECONDS);
                timeoutSecs = Math.min(timeoutSecs, MAX_TIMEOUT_SECONDS);

                HttpRequest request = HttpRequest.newBuilder(uri).header("User-Agent", UA)
                                                .header("Accept", acceptHeader(format))
                                                .header("Accept-Language", "en-US,en;q=0.9")
                                                .timeout(Duration.ofSeconds(timeoutSecs)).GET().build();

                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());

                byte[] body = response.body();
                if (body.length > MAX_RESPONSE_SIZE) {
                    return ToolResult.error("Response too large (exceeds 5MB limit)");
                }
                if (String.valueOf(response.statusCode()).startsWith("4")
                                                || String.valueOf(response.statusCode()).startsWith("5")) {
                    return ToolResult.error("HTTP " + response.statusCode());
                }

                String contentType = response.headers().firstValue("content-type").orElse("");
                String mime = contentType.split(";")[0].trim().toLowerCase();
                String content = new String(body, detectCharset(contentType, body));
                String output = convertContent(content, mime, format);

                if (output.length() > Util.DEFAULT_MAX_CHARS) {
                    var truncated = Util.truncateToFile(output, Util.DEFAULT_MAX_CHARS, "pijota-webfetch-");
                    return ToolResult.success(truncated.text(), truncated.details());
                }
                return ToolResult.success(output);
            } catch (HttpTimeoutException e) {
                return ToolResult.error("Request timed out");
            } catch (IOException e) {
                return ToolResult.error("Fetch error: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("Fetch interrupted");
            }
        });
    }

    private static String acceptHeader(String format) {
        return switch (format) {
            case "markdown" -> "text/markdown;q=1.0, text/plain;q=0.8, text/html;q=0.7, */*;q=0.1";
            case "text" -> "text/plain;q=1.0, text/html;q=0.8, */*;q=0.1";
            case "html" -> "text/html;q=1.0, text/plain;q=0.8, */*;q=0.1";
            default -> "text/html,text/plain,*/*;q=0.8";
        };
    }

    private static String convertContent(String content, String mime, String format) {
        boolean isHtml = mime.contains("html") || content.strip().toLowerCase().startsWith("<!doctype")
                                        || content.strip().toLowerCase().startsWith("<html");
        return switch (format) {
            case "markdown" -> isHtml ? htmlToMarkdown(content) : content;
            case "text" -> isHtml ? htmlToText(content) : content;
            case "html" -> content;
            default -> isHtml ? htmlToMarkdown(content) : content;
        };
    }

    private static String htmlToMarkdown(String html) {
        String text = html.replaceAll("(?is)<script[^>]*>.*?</script>", "")
                                        .replaceAll("(?is)<style[^>]*>.*?</style>", "")
                                        .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", "")
                                        .replaceAll("(?is)<head[^>]*>.*?</head>", "");

        text = text.replaceAll("(?is)</p>", "\n\n");
        text = text.replaceAll("(?is)<br\\s*/?>", "\n");
        text = text.replaceAll("(?is)</?h[1-6][^>]*>", "\n\n**");
        text = text.replaceAll("(?is)</li>", "\n");
        text = text.replaceAll("(?is)</tr>", "\n");
        text = text.replaceAll("(?is)</(div|section|article|header|footer|main|nav)>", "\n");

        text = text.replaceAll("(?is)<a\\s+[^>]*href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>(.*?)</a>", "[$2]($1)");
        text = text.replaceAll("(?is)<img\\s+[^>]*src\\s*=\\s*['\"]([^'\"]+)['\"][^>]*alt\\s*=\\s*['\"]([^'\"]*)['\"][^>]*>",
                                        "![$2]($1)");
        text = text.replaceAll("(?is)<img\\s+[^>]*alt\\s*=\\s*['\"]([^'\"]*)['\"][^>]*src\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>",
                                        "![$1]($2)");
        text = text.replaceAll("(?is)<strong\\b[^>]*>(.*?)</strong>", "**$1**");
        text = text.replaceAll("(?is)<b\\b[^>]*>(.*?)</b>", "**$1**");
        text = text.replaceAll("(?is)<em\\b[^>]*>(.*?)</em>", "*$1*");
        text = text.replaceAll("(?is)<i\\b[^>]*>(.*?)</i>", "*$1*");
        text = text.replaceAll("(?is)<code\\b[^>]*>(.*?)</code>", "`$1`");
        text = text.replaceAll("(?is)<pre\\b[^>]*>(.*?)</pre>", "\n```\n$1\n```\n");

        text = text.replaceAll("(?is)<[^>]+>", "");

        text = text.replaceAll("&amp;", "&").replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&quot;", "\"")
                                        .replaceAll("&#39;", "'").replaceAll("&apos;", "'").replaceAll("&nbsp;", " ");

        text = text.replaceAll("\\n{3,}", "\n\n").strip();
        return text;
    }

    private static String htmlToText(String html) {
        String text = html.replaceAll("(?is)<script[^>]*>.*?</script>", "")
                                        .replaceAll("(?is)<style[^>]*>.*?</style>", "")
                                        .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", "");
        text = text.replaceAll("(?is)<br\\s*/?>", "\n");
        text = text.replaceAll("(?is)<p[^>]*>", "\n\n");
        text = text.replaceAll("(?is)</(div|section|article|header|footer|main|nav|li|tr)>", "\n");
        text = text.replaceAll("(?is)<[^>]+>", "");
        text = text.replaceAll("&amp;", "&").replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&quot;", "\"")
                                        .replaceAll("&apos;", "'").replaceAll("&nbsp;", " ");
        text = text.replaceAll("\\n{3,}", "\n\n").strip();
        return text;
    }

    private static String detectCharset(String contentType, byte[] body) {
        var m = Pattern.compile("(?i)charset\\s*=\\s*([^\\s;]+)").matcher(contentType);
        if (m.find()) {
            try {
                return java.nio.charset.Charset.forName(m.group(1)).name();
            } catch (Exception ignored) {}
        }
        if (body.length >= 3 && body[0] == (byte) 0xEF && body[1] == (byte) 0xBB && body[2] == (byte) 0xBF)
            return StandardCharsets.UTF_8.name();
        return StandardCharsets.UTF_8.name();
    }
}
