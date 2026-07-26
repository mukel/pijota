package org.pijota.util;

import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class NetTest {

    @Test
    void lanAddressNeverThrowsAndIsNullOrASiteLocalIpv4() throws Exception {
        // Called on every --web / remote-control start; must be resilient to any host network config.
        String addr = assertDoesNotThrow(Net::lanAddress);
        if (addr != null) {
            InetAddress ip = InetAddress.getByName(addr); // numeric literal → parsed, no DNS lookup
            assertInstanceOf(Inet4Address.class, ip, "site-local LAN address should be IPv4");
            assertTrue(ip.isSiteLocalAddress(), "must be a private/site-local address: " + addr);
        }
    }
}
