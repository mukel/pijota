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
    private static final boolean[][] PJ_MARK = parseMark("""
                                    ###..#
                                    #..#.#
                                    ###..#
                                    #....#
                                    #..###
                                    """);

    /** QR code for {@code text} as terminal lines (medium error correction). */
    public static List<String> render(String text) {
        return render(QrCode.encodeText(text, QrCode.Ecc.MEDIUM), null);
    }

    /** QR code for {@code text} with a tiny centered {@code pj} mark burned into the modules. */
    public static List<String> renderPj(String text) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.HIGH);
        return render(qr, centered(PJ_MARK, qr.size));
    }

    private static List<String> render(QrCode qr, Overlay overlay) {
        int size = qr.size;
        var lines = new ArrayList<String>();
        for (int y = -QUIET_ZONE; y < size + QUIET_ZONE; y += 2) {
            var sb = new StringBuilder();
            for (int x = -QUIET_ZONE; x < size + QUIET_ZONE; x++) {
                boolean top = dark(qr, overlay, x, y, size);
                boolean bottom = dark(qr, overlay, x, y + 1, size);
                sb.append(top ? (bottom ? '█' : '▀') : (bottom ? '▄' : ' '));
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    private static boolean dark(QrCode qr, Overlay overlay, int x, int y, int size) {
        if (x < 0 || x >= size || y < 0 || y >= size)
            return false;
        if (overlay != null && overlay.contains(x, y))
            return overlay.dark(x, y);
        return qr.getModule(x, y);
    }

    private static Overlay centered(boolean[][] mark, int qrSize) {
        int margin = 1;
        int width = mark[0].length + margin * 2;
        int height = mark.length + margin * 2;
        return new Overlay((qrSize - width) / 2, (qrSize - height) / 2, width, height, mark, margin);
    }

    private static boolean[][] parseMark(String raw) {
        String[] rows = raw.strip().lines().map(String::strip).toArray(String[]::new);
        var mark = new boolean[rows.length][rows[0].length()];
        for (int y = 0; y < rows.length; y++)
            for (int x = 0; x < rows[y].length(); x++)
                mark[y][x] = rows[y].charAt(x) != '.';
        return mark;
    }

    private record Overlay(int x, int y, int width, int height, boolean[][] mark, int margin) {
        boolean contains(int px, int py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }

        boolean dark(int px, int py) {
            int mx = px - x - margin;
            int my = py - y - margin;
            return mx >= 0 && mx < mark[0].length && my >= 0 && my < mark.length && mark[my][mx];
        }
    }

    /** A "scan to open" banner: an indented caption, the URL, and its QR, the one place that lays this out. */
    public static List<String> scannable(String caption, String url) {
        var out = new ArrayList<String>();
        out.add("   " + caption);
        out.add("   " + url);
        out.addAll(render(url));
        return out;
    }

    /** A branded Pijota QR banner. Keep the URL short enough that the center mark remains scan-friendly. */
    public static List<String> scannablePj(String caption, String url) {
        var out = new ArrayList<String>();
        out.add("   " + caption);
        out.add("   " + url);
        out.addAll(renderPj(url));
        return out;
    }
}
