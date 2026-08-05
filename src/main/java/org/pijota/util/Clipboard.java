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
    /**
     * How long to give the command to fail before assuming it is holding the selection.
     *
     * <p>
     * Both outcomes are decided in about 25 milliseconds in practice -- a working {@code wl-copy} forks and exits, and
     * one with no compositor to talk to exits non-zero -- so this is roughly twenty times the headroom either needs. It
     * was five seconds, which is only reachable by a command that neither forks nor fails, and that is exactly the case
     * where waiting is worst: {@code /copy} runs on the thread drawing the interface, so the whole terminal sat still
     * for five seconds and then reported a success that had not happened.
     */
    private static final long SETTLE_MS = 500;

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
            return settled(p);
        } catch (Exception e) {
            return false; // a missing or misbehaving tool must never crash the caller, fall through to the next
        }
    }

    /**
     * Whether the command that has just been handed the text counts as having taken it.
     *
     * <p>
     * {@code pbcopy} and {@code clip} read the text and exit, so their status says it. {@code xclip} and
     * {@code wl-copy} hand the selection to a child and the parent exits, and a build that does not fork stays running
     * to serve the selection itself -- which is also success, and is indistinguishable from a hang. Still running at
     * the deadline is therefore read as success; a command that is going to fail has already exited by then.
     */
    static boolean settled(Process p) throws InterruptedException {
        if (!p.waitFor(SETTLE_MS, TimeUnit.MILLISECONDS))
            return true;
        return p.exitValue() == 0;
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
