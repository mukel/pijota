package org.pijota.tui;

import org.jline.terminal.Terminal;

/**
 * The <a href="https://sw.kovidgoyal.net/kitty/keyboard-protocol/">Kitty keyboard protocol</a>, which makes modifiers
 * unambiguous. Without it a terminal cannot say "Shift+Enter" at all, and Esc is indistinguishable from the start of an
 * Alt-chord except by waiting a few milliseconds and hoping, which is exactly the guess that misfires over a slow SSH
 * link and leaves the TUI acting on a key nobody pressed.
 *
 * <p>
 * Only flag 1 (disambiguate escape codes) is requested. Flag 2 would add key-release events, which every handler would
 * then have to filter to avoid acting on a keypress twice; nothing here needs them.
 *
 * <p>
 * Negotiation follows the sequence Kitty documents: push the flags we want, ask what is now set, then send a plain
 * Device Attributes request as a sentinel. A terminal that speaks the protocol answers the middle query first; one that
 * doesn't skips it and answers only DA, so support is settled by what comes back rather than by a startup timeout.
 */
final class KittyKeyboard {
    private KittyKeyboard() {}

    /** Push flag 1 onto the terminal's keyboard-mode stack. */
    static final String ENABLE = "\033[>1u";

    /** Pop our entry. Safe to send unconditionally: on a terminal that never pushed, it is ignored. */
    static final String DISABLE = "\033[<u";

    private static final String QUERY = ENABLE + "\033[?u\033[c";

    /** Total time to wait for the terminal to answer. A terminal that answers at all answers immediately. */
    private static final int NEGOTIATION_BUDGET_MS = 150;

    /**
     * Asks the terminal to enable the protocol and reports whether it did. Consumes the replies so they never reach the
     * key loop. Never throws and never blocks longer than {@link #NEGOTIATION_BUDGET_MS}: a pipe, a dumb terminal, or a
     * terminal that ignores both queries simply yields {@code false}.
     */
    static boolean negotiate(Terminal terminal) {
        try {
            terminal.writer().print(QUERY);
            terminal.writer().flush();
            long deadline = System.currentTimeMillis() + NEGOTIATION_BUDGET_MS;
            var seq = new StringBuilder();
            while (System.currentTimeMillis() < deadline) {
                int c = terminal.reader().read(Math.max(1, deadline - System.currentTimeMillis()));
                if (c < 0)
                    break;
                seq.append((char) c);
                // Both replies are CSI ? ... <final>: 'u' is the protocol's flags report, 'c' the DA sentinel that
                // means we got all the way to the end without one.
                if (c == 'u')
                    return seq.toString().contains("\033[?");
                if (c == 'c')
                    return false;
                if (seq.length() > 64) // not a reply we understand; stop accumulating
                    break;
            }
        } catch (Exception | Error ignored) {
            // A terminal that cannot be queried is simply one without the protocol.
        }
        return false;
    }
}
