package org.pijota.util;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProxiesTest {

    @AfterEach
    void clearProxyProperty() {
        System.clearProperty("pijota.proxy");
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
        var address = (java.net.InetSocketAddress) proxies.get(0).address();
        assertEquals("proxy.example", address.getHostString());
        assertEquals(8080, address.getPort());
    }

    @Test
    void credentialsAndPathInProxyUrlAreStripped() {
        System.setProperty("pijota.proxy", "http://user:pass@proxy.example:3128/");
        var address = (java.net.InetSocketAddress) Proxies.selector().select(URI.create("https://x")).get(0).address();
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
        assertEquals(java.util.Optional.of(Proxies.DEFAULT_CONNECT_TIMEOUT),
                                        Proxies.httpClientBuilder().build().connectTimeout());
    }

    /** A caller with its own configured timeout still wins; the builder only supplies the floor. */
    @Test
    void aCallerCanStillChooseItsOwnTimeout() {
        var own = java.time.Duration.ofSeconds(3);
        assertEquals(java.util.Optional.of(own),
                                        Proxies.httpClientBuilder().connectTimeout(own).build().connectTimeout());
    }
}
