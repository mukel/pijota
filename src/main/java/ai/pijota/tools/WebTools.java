package ai.pijota.tools;

import ai.pijota.util.Env;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared helpers for the web tools ({@link WebfetchTool}/{@link WebsearchTool}): a browser User-Agent, the default HTTP
 * client, and HTML-entity decoding. Keeps a UA bump, a client-config change, or an entity-set fix in one place (all
 * three previously drifted across the two tools).
 */
final class WebTools {

    private WebTools() {}

    /** Desktop Chrome User-Agent — some sites gate content behind a browser-like UA. */
    static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                    + " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";

    /** Connect timeout shared by both web tools; tune via pijota.web.connect-timeout / PIJOTA_WEB_CONNECT_TIMEOUT. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(
                                    Env.seconds("pijota.web.connect-timeout", "PIJOTA_WEB_CONNECT_TIMEOUT", 10));

    /** The default HTTP client for the web tools: follow redirects, configurable connect timeout. */
    static HttpClient defaultClient() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(CONNECT_TIMEOUT)
                                        .build();
    }

    /** Decodes the common HTML entities — one canonical set for both tools ({@code &amp;} first, as before). */
    static String decodeEntities(String text) {
        if (text == null)
            return null;
        return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                                        .replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ");
    }
}
