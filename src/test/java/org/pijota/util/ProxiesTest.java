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
        // Env proxy vars aren't set in the test JVM, and the property is cleared — so nothing routes through a proxy.
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
}
