package org.pijota.util;

import io.nayuki.qrcodegen.QrCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a QR code as terminal text using Unicode half-blocks (two QR modules per character row), so a phone can scan
 * a URL straight off the terminal. Dark modules are drawn as blocks with a light quiet zone; phone scanners handle the
 * light-on-dark terminal rendering fine.
 */
public final class QrTerminal {
    private QrTerminal() {}

    private static final int QUIET_ZONE = 2;

    /** QR code for {@code text} as terminal lines (medium error correction). */
    public static List<String> render(String text) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        var lines = new ArrayList<String>();
        for (int y = -QUIET_ZONE; y < qr.size + QUIET_ZONE; y += 2) {
            var sb = new StringBuilder();
            for (int x = -QUIET_ZONE; x < qr.size + QUIET_ZONE; x++) {
                boolean top = dark(qr, x, y);
                boolean bottom = dark(qr, x, y + 1);
                sb.append(top ? (bottom ? '█' : '▀') : (bottom ? '▄' : ' '));
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    private static boolean dark(QrCode qr, int x, int y) {
        return x >= 0 && x < qr.size && y >= 0 && y < qr.size && qr.getModule(x, y);
    }

    /** A "scan to open" banner: an indented caption, the URL, and its QR, the one place that lays this out. */
    public static List<String> scannable(String caption, String url) {
        var out = new ArrayList<String>();
        out.add("   " + caption);
        out.add("   " + url);
        out.addAll(render(url));
        return out;
    }
}
