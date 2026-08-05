package org.pijota.util;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;

/** Network facts about this host that the interface needs, such as the address to print in a QR code. */
public final class Net {
    private Net() {}

    /**
     * The machine's primary site-local IPv4 address (e.g. {@code 192.168.x.x}), the address a phone on the same LAN
     * would use to reach this host. Returns {@code null} when none can be determined.
     */
    public static String lanAddress() {
        try {
            for (var ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual())
                    continue;
                for (var addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress())
                        return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // fall through to null. Caller decides the fallback
        }
        return null;
    }
}
