package ai.pijota.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Central HTTP(S) proxy configuration. A proxy is taken from the first of: {@code -Dpijota.proxy} /
 * {@code PIJOTA_PROXY}, then the conventional {@code HTTPS_PROXY}, {@code HTTP_PROXY}, {@code ALL_PROXY} environment
 * variables (upper- or lower-case). {@code NO_PROXY} is a comma-separated list of host suffixes that bypass the proxy.
 *
 * <p>
 * Callers that build a {@link HttpClient} route it through {@link #httpClientBuilder()}. Because {@code HttpClient}
 * ignores the JVM's proxy system properties, {@link #installSystemProperties()} mirrors the configured proxy into
 * {@code https.proxyHost}/etc. at startup so URLConnection-based paths — and providers whose client we can't configure
 * directly (Gemini) — route through it rather than silently bypassing it.
 */
public final class Proxies {
    private Proxies() {}

    /**
     * The configured proxy selector, or {@code null} when no proxy is set. Resolved from the environment on each call
     * (cheap, and never during native-image build), so it always reflects the live configuration.
     */
    public static ProxySelector selector() {
        var host = proxyHost();
        if (host == null)
            return null;
        var proxies = List.of(new Proxy(Proxy.Type.HTTP, host));
        var bypass = noProxyHosts();
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                String h = uri == null ? null : uri.getHost();
                return h != null && isBypassed(h, bypass) ? List.of(Proxy.NO_PROXY) : proxies;
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException e) {}
        };
    }

    /** A fresh {@link HttpClient.Builder}, with the configured proxy applied when one is set. */
    public static HttpClient.Builder httpClientBuilder() {
        var builder = HttpClient.newBuilder();
        var s = selector();
        if (s != null)
            builder.proxy(s);
        return builder;
    }

    /**
     * Mirror the configured proxy into the JVM's {@code http(s).proxyHost/Port} + {@code http.nonProxyHosts} system
     * properties, without clobbering ones the user set explicitly. {@link HttpClient} ignores these, but
     * URLConnection-based paths and providers we can't configure directly rely on them. A no-op when no proxy is set.
     */
    public static void installSystemProperties() {
        var host = proxyHost();
        if (host == null)
            return;
        setIfAbsent("http.proxyHost", host.getHostString());
        setIfAbsent("http.proxyPort", Integer.toString(host.getPort()));
        setIfAbsent("https.proxyHost", host.getHostString());
        setIfAbsent("https.proxyPort", Integer.toString(host.getPort()));
        var bypass = noProxyHosts();
        if (!bypass.isEmpty())
            setIfAbsent("http.nonProxyHosts", String.join("|", bypass));
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null)
            System.setProperty(key, value);
    }

    static boolean isBypassed(String host, List<String> bypass) {
        String h = host.toLowerCase(Locale.ROOT);
        for (String entry : bypass)
            if (h.equals(entry) || h.endsWith("." + entry))
                return true;
        return false;
    }

    /** The configured proxy endpoint, or {@code null} when none is set. */
    private static InetSocketAddress proxyHost() {
        String url = Util.coalesce(Env.get("pijota.proxy", "PIJOTA_PROXY"), env("HTTPS_PROXY", "https_proxy"),
                                        env("HTTP_PROXY", "http_proxy"), env("ALL_PROXY", "all_proxy"));
        return url == null ? null : parse(url);
    }

    /** {@code NO_PROXY} host suffixes, lower-cased with any leading dot stripped; empty when unset. */
    private static List<String> noProxyHosts() {
        String raw = env("NO_PROXY", "no_proxy");
        if (raw == null)
            return List.of();
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                                        .map(s -> s.startsWith(".") ? s.substring(1) : s)
                                        .map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    /** Parse {@code [scheme://][user:pass@]host:port} into an address; {@code null} when unparseable. */
    private static InetSocketAddress parse(String value) {
        String v = value.trim();
        int scheme = v.indexOf("://");
        if (scheme >= 0)
            v = v.substring(scheme + 3);
        int at = v.lastIndexOf('@'); // strip any credentials — auth is handled separately, not by the address
        if (at >= 0)
            v = v.substring(at + 1);
        int slash = v.indexOf('/');
        if (slash >= 0)
            v = v.substring(0, slash);
        int colon = v.lastIndexOf(':');
        try {
            if (colon < 0)
                return v.isEmpty() ? null : InetSocketAddress.createUnresolved(v, 80);
            String h = v.substring(0, colon);
            int port = Integer.parseInt(v.substring(colon + 1));
            return h.isEmpty() ? null : InetSocketAddress.createUnresolved(h, port);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String env(String... names) {
        for (String n : names) {
            String v = System.getenv(n);
            if (v != null && !v.isBlank())
                return v;
        }
        return null;
    }
}
