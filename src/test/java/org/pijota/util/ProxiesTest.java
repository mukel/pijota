package org.pijota.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ProxiesTest {

    @AfterEach
    void clearProxyProperty() {
        System.clearProperty("pijota.proxy");
        Proxies.configure(null); // process-wide, like the property above: it must not cross between tests
    }

    @Test
    void noProxyConfiguredMeansNoSelector() {
        // Env proxy vars aren't set in the test JVM, and the property is cleared, so nothing routes through a proxy.
        assertNull(Proxies.selector());
    }

    @Test
    void proxyPropertyIsParsedAndSelectedForRequests() {
        System.setProperty("pijota.proxy", "http://proxy.example:8080");
        var selector = Proxies.selector();
        assertNotNull(selector);

        var proxies = selector.select(URI.create("https://api.openai.com/v1"));
        assertEquals(1, proxies.size());
        var address = (InetSocketAddress) proxies.get(0).address();
        assertEquals("proxy.example", address.getHostString());
        assertEquals(8080, address.getPort());
    }

    @Test
    void credentialsAndPathInProxyUrlAreStripped() {
        System.setProperty("pijota.proxy", "http://user:pass@proxy.example:3128/");
        var address = (InetSocketAddress) Proxies.selector().select(URI.create("https://x")).get(0).address();
        assertEquals("proxy.example", address.getHostString());
        assertEquals(3128, address.getPort());
    }

    @Test
    void malformedProxyUrlYieldsNoSelector() {
        System.setProperty("pijota.proxy", "://:");
        assertNull(Proxies.selector());
    }

    @Test
    void bypassMatchesExactHostAndSubdomainSuffixes() {
        var bypass = List.of("localhost", "internal.corp");
        assertTrue(Proxies.isBypassed("localhost", bypass), "exact host");
        assertTrue(Proxies.isBypassed("api.internal.corp", bypass), "subdomain suffix");
        assertFalse(Proxies.isBypassed("api.openai.com", bypass), "unrelated host is proxied");
        assertFalse(Proxies.isBypassed("notinternal.corp", bypass), "suffix must be on a dot boundary");
    }

    /**
     * Java bounds a connect attempt only if you ask: with no connectTimeout the OS decides, which on Linux is minutes
     * of SYN retries. Three of the seven callers had forgotten to ask -- the model refresher and all three OAuth login
     * clients -- so an unreachable provider left {@code /refresh} sitting on "refreshing models…" with nothing to
     * cancel, and did the same to a login. The bound lives in the shared builder because forgetting it is silent and
     * only shows on a network that does not answer.
     */
    @Test
    void everyClientFromTheSharedBuilderBoundsItsConnectAttempt() {
        assertEquals(Optional.of(Proxies.connectTimeout()), Proxies.httpClientBuilder().build().connectTimeout());
    }

    /** A caller with its own configured timeout still wins; the builder only supplies the floor. */
    @Test
    void aCallerCanStillChooseItsOwnTimeout() {
        var own = Duration.ofSeconds(3);
        assertEquals(Optional.of(own), Proxies.httpClientBuilder().connectTimeout(own).build().connectTimeout());
    }

    /**
     * Settings sit between the two kinds of environment. A flag or PIJOTA_PROXY is aimed at this run and wins; a proxy
     * written down in settings.json beats HTTPS_PROXY, which belongs to the shell and was probably exported for
     * something else entirely.
     */
    @Test
    void theProxySettingIsUsedButAnExplicitFlagStillWins() {
        Proxies.configure("http://from-settings:3128");
        assertNotNull(Proxies.selector(), "the setting alone configures a proxy");
        assertEquals("from-settings", host(Proxies.selector()));

        System.setProperty("pijota.proxy", "http://from-flag:8080");
        assertEquals("from-flag", host(Proxies.selector()), "the flag is aimed at this run, so it wins");

        System.clearProperty("pijota.proxy");
        Proxies.configure("   ");
        assertNull(Proxies.selector(), "a blank setting is not a proxy");
    }

    private static String host(java.net.ProxySelector selector) {
        var address = (java.net.InetSocketAddress) selector.select(java.net.URI.create("https://api.test/v1"))
                                        .getFirst().address();
        return address.getHostString();
    }
}
