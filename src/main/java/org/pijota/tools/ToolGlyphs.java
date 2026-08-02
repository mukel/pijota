package org.pijota.tools;

/**
 * The marks that open a tool line. The tool's name is printed immediately after, so the glyph does not need to say
 * <em>which</em> tool ran: it says what the call did to your machine. Scanning a transcript then answers the question
 * that actually matters when reviewing an agent's work, which is where it wrote.
 *
 * <pre>
 *   ○  looked at something          read, ls, glob, grep
 *   ●  changed something on disk    write, edit, apply_patch
 *   $  ran a command                bash, jobs
 *   &#64;  went to the network          webfetch, websearch
 *   ◆  the agent's own bookkeeping  task, todo, slash commands
 * </pre>
 *
 * <p>
 * A failed call is opened by {@code ✖} instead, which completes the rule rather than breaking it: the effect mark
 * states what the call did, and a call that failed did not do it.
 *
 * <p>
 * Hollow versus filled is the load-bearing distinction, and it is the one a reader can use at a glance. These are also
 * single-width and theme-coloured: the emoji they replaced were the only element in the interface that ignored the
 * palette entirely, and being double-width they were the one thing that could shift a column on a terminal that
 * disagreed about their size.
 */
public final class ToolGlyphs {
    private ToolGlyphs() {}

    /** Read-only: the call inspected something and changed nothing. */
    public static final String INSPECT = "○";

    /** The call wrote to disk. The one mark worth spotting when reviewing what an agent did. */
    public static final String MUTATE = "●";

    /** The call ran a command. The shell's own prompt character, which needs no explanation. */
    public static final String RUN = "$";

    /** The call left the machine. */
    public static final String NETWORK = "@";

    /** The agent's own bookkeeping: no file touched, nothing run, nothing fetched. */
    public static final String AGENT = "◆";
}
