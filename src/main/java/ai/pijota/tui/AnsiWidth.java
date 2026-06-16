package ai.pijota.tui;

import java.util.ArrayList;
import java.util.List;

final class AnsiWidth {
    private static final String RESET = "\033[0m";

    private AnsiWidth() {}

    public static int visibleWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == 0x1b) {
                i = skipEscape(text, i);
            } else if (ch == '\t') {
                width += 4 - (width % 4);
            } else if (!Character.isISOControl(ch)) {
                width += charWidth(text, i);
                if (Character.isHighSurrogate(ch))
                    i++;
            }
        }
        return width;
    }

    private static int charWidth(String text, int index) {
        int cp = text.codePointAt(index);
        if (cp == 0x200D)
            return 1;
        if (cp >= 0x1100 && cp <= 0x115F)
            return 2;
        if (cp >= 0x2E80 && cp <= 0xA4CF)
            return 2;
        if (cp >= 0xA960 && cp <= 0xA97C)
            return 2;
        if (cp >= 0xAC00 && cp <= 0xD7A3)
            return 2;
        if (cp >= 0xF900 && cp <= 0xFAFF)
            return 2;
        if (cp >= 0xFE10 && cp <= 0xFE6F)
            return 2;
        if (cp >= 0xFF01 && cp <= 0xFFE6)
            return 2;
        if (cp >= 0x1F000 && cp <= 0x1FAFF)
            return 2;
        if (cp >= 0x2300 && cp <= 0x24FF)
            return 2;
        if (cp >= 0x2580 && cp <= 0x27BF)
            return 2;
        if (cp >= 0x2B00 && cp <= 0x2BFF)
            return 2;
        return 1;
    }

    public static String truncateVisual(String text, int maxWidth) {
        if (maxWidth <= 0)
            return "";
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == 0x1b) {
                int skipped = skipEscape(text, i);
                i = skipped;
            } else if (ch == '\t') {
                width += 4 - (width % 4);
            } else if (!Character.isISOControl(ch)) {
                width += charWidth(text, i);
                if (Character.isHighSurrogate(ch))
                    i++;
            }
            if (width >= maxWidth)
                return text.substring(0, i + 1) + "\033[0m";
        }
        return text;
    }

    public static List<String> wrapPreservingAnsi(String text, int maxWidth) {
        if (maxWidth <= 0)
            return List.of("");
        var out = new ArrayList<String>();
        var chunk = new StringBuilder();
        int visible = 0;
        var sgr = new SgrState();
        String osc8Url = null;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == 0x1b) {
                int start = i;
                int end = skipEscape(text, i);
                String esc = text.substring(start, end + 1);
                chunk.append(esc);
                sgr.apply(esc);
                osc8Url = updateOsc8(esc, osc8Url);
                i = end;
            } else if (ch == '\t') {
                // Emit spaces, not a literal tab: a tab moves the terminal cursor forward WITHOUT overwriting the cells
                // it skips, so stale content underneath (e.g. a separator a non-blanking region erase left in place)
                // bleeds through. Spaces overwrite every cell; the visual width is unchanged.
                int advance = 4 - (visible % 4);
                chunk.append(" ".repeat(advance));
                visible += advance;
            } else if (ch == '\n') {
                if (osc8Url != null)
                    chunk.append("\033]8;;\033\\");
                out.add(closeChunk(chunk, sgr.active()));
                chunk.setLength(0);
                chunk.append(sgr.ansi());
                if (osc8Url != null)
                    chunk.append("\033]8;;").append(osc8Url).append("\033\\");
                visible = 0;
            } else if (Character.isISOControl(ch)) {
                chunk.append(ch);
            } else {
                int cw = charWidth(text, i);
                if (visible > 0 && visible + cw > maxWidth) {
                    if (osc8Url != null)
                        chunk.append("\033]8;;\033\\");
                    out.add(closeChunk(chunk, sgr.active()));
                    chunk.setLength(0);
                    chunk.append(sgr.ansi());
                    if (osc8Url != null)
                        chunk.append("\033]8;;").append(osc8Url).append("\033\\");
                    visible = 0;
                }
                chunk.append(ch);
                if (Character.isHighSurrogate(ch) && i + 1 < text.length()) {
                    chunk.append(text.charAt(i + 1));
                    i++;
                }
                visible += cw;
            }
        }
        if (osc8Url != null)
            chunk.append("\033]8;;\033\\");
        if (!chunk.isEmpty())
            out.add(closeChunk(chunk, sgr.active()));
        return out.isEmpty() ? List.of("") : out;
    }

    private static String updateOsc8(String esc, String current) {
        if (esc.length() < 6)
            return current;
        if (esc.charAt(0) == 0x1b && esc.charAt(1) == ']' && esc.charAt(2) == '8') {
            String inner = esc.substring(4, esc.length() - 2);
            return inner.length() > 1 ? inner.substring(1) : null;
        }
        return current;
    }

    private static String closeChunk(StringBuilder chunk, boolean active) {
        return active ? chunk + RESET : chunk.toString();
    }

    private static int skipEscape(String text, int escIndex) {
        int i = escIndex + 1;
        if (i >= text.length())
            return escIndex;
        char ch = text.charAt(i);
        if (ch == '[') {
            i++;
            while (i < text.length()) {
                ch = text.charAt(i);
                if (ch >= '@' && ch <= '~')
                    return i;
                i++;
            }
            return text.length() - 1;
        }
        if (ch == ']') {
            i++;
            while (i < text.length()) {
                ch = text.charAt(i);
                if (ch == '\007')
                    return i;
                if (ch == 0x1b && i + 1 < text.length() && text.charAt(i + 1) == '\\')
                    return i + 1;
                i++;
            }
            return text.length() - 1;
        }
        return i;
    }

    private static final class SgrState {
        private final boolean[] attrs = new boolean[8];
        private String fg = "";
        private String bg = "";

        void apply(String esc) {
            if (!esc.startsWith("\033[") || !esc.endsWith("m"))
                return;
            String raw = esc.substring(2, esc.length() - 1);
            if (raw.isEmpty()) {
                reset();
                return;
            }
            String[] parts = raw.split(";");
            for (int i = 0; i < parts.length; i++) {
                int code = parse(parts[i], 0);
                switch (code) {
                    case 0 -> reset();
                    case 1, 2, 3, 4, 7 -> attrs[code] = true;
                    case 22 -> {
                        attrs[1] = false;
                        attrs[2] = false;
                    }
                    case 23 -> attrs[3] = false;
                    case 24 -> attrs[4] = false;
                    case 27 -> attrs[7] = false;
                    case 30, 31, 32, 33, 34, 35, 36, 37, 90, 91, 92, 93, 94, 95, 96, 97 -> fg = parts[i];
                    case 39 -> fg = "";
                    case 40, 41, 42, 43, 44, 45, 46, 47, 100, 101, 102, 103, 104, 105, 106, 107 -> bg = parts[i];
                    case 49 -> bg = "";
                    case 38, 48 -> {
                        String color = readExtendedColor(parts, i);
                        if (!color.isEmpty()) {
                            if (code == 38)
                                fg = color;
                            else
                                bg = color;
                            i += colorAdvance(color);
                        }
                    }
                    default -> {}
                }
            }
        }

        boolean active() {
            if (!fg.isEmpty() || !bg.isEmpty())
                return true;
            for (boolean attr : attrs)
                if (attr)
                    return true;
            return false;
        }

        String ansi() {
            if (!active())
                return "";
            var sb = new StringBuilder("\033[");
            boolean first = true;
            for (int i : new int[] {1, 2, 3, 4, 7}) {
                if (!attrs[i])
                    continue;
                if (!first)
                    sb.append(';');
                sb.append(i);
                first = false;
            }
            if (!fg.isEmpty()) {
                if (!first)
                    sb.append(';');
                sb.append(fg);
                first = false;
            }
            if (!bg.isEmpty()) {
                if (!first)
                    sb.append(';');
                sb.append(bg);
            }
            return sb.append('m').toString();
        }

        private void reset() {
            for (int i = 0; i < attrs.length; i++)
                attrs[i] = false;
            fg = "";
            bg = "";
        }

        private static String readExtendedColor(String[] parts, int i) {
            if (i + 2 < parts.length && parse(parts[i + 1], -1) == 5) {
                return parts[i] + ";" + parts[i + 1] + ";" + parts[i + 2];
            }
            if (i + 4 < parts.length && parse(parts[i + 1], -1) == 2) {
                return parts[i] + ";" + parts[i + 1] + ";" + parts[i + 2] + ";" + parts[i + 3] + ";" + parts[i + 4];
            }
            return "";
        }

        private static int colorAdvance(String color) {
            return color.startsWith("38;2;") || color.startsWith("48;2;") ? 4 : 2;
        }

        private static int parse(String value, int fallback) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
