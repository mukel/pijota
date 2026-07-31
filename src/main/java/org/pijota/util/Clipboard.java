package org.pijota.util;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Copies text to the OS clipboard by piping it to the platform's native clipboard command: {@code pbcopy} (macOS),
 * {@code clip} (Windows), or {@code wl-copy} / {@code xclip} / {@code xsel} (Linux), whichever is present. This
 * deliberately avoids {@code java.awt.Toolkit}: on macOS it forces an AWT/Dock startup that steals focus from the
 * terminal, and it is absent from the GraalVM native image (pijota's primary distribution).
 *
 * <p>
 * Output is discarded rather than captured: {@code xclip}/{@code wl-copy} fork a child that keeps stdout open to serve
 * the selection, which would block a capturing reader, so we redirect to the null device and never read it.
 */
public final class Clipboard {
    private static final long TIMEOUT_MS = 5000;

    private Clipboard() {}

    /** Copies {@code text} to the clipboard, returning {@code true} on success. Never throws. */
    public static boolean copy(String text) {
        if (text == null)
            return false;
        for (List<String> cmd : candidates())
            if (Executables.isAvailable(cmd.get(0)) && write(cmd, text))
                return true;
        return false;
    }

    private static boolean write(List<String> cmd, String text) {
        try {
            Process p = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (var stdin = p.getOutputStream()) {
                stdin.write(text.getBytes(StandardCharsets.UTF_8));
            }
            // pbcopy/clip exit after reading; xclip/wl-copy fork a persistent child and the parent exits. If the parent
            // hasn't returned by the timeout it is that persistent daemon holding the selection, so the copy succeeded.
            if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                return true;
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false; // a missing or misbehaving tool must never crash the caller, fall through to the next
        }
    }

    /** The clipboard commands to try, best-first, for this OS. */
    private static List<List<String>> candidates() {
        if (Platform.isMac())
            return List.of(List.of("pbcopy"));
        if (Platform.isWindows())
            return List.of(List.of("clip"));
        return List.of(List.of("wl-copy"), List.of("xclip", "-selection", "clipboard"),
                                        List.of("xsel", "--clipboard", "--input"));
    }
}
