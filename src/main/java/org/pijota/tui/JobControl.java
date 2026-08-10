package org.pijota.tui;

import org.pijota.util.ParentWatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stopping this process the way a shell expects, and handing a signal back to whoever held it before.
 *
 * <p>
 * Raw mode turns off ISIG, so Ctrl+Z never reaches the kernel as SIGTSTP and pijota raises it itself. Deciding who to
 * stop and building the {@code kill} argv is arithmetic over two pids and needs no terminal, so it lives here; the half
 * that does hold the terminal -- dropping to cooked mode and re-asserting it after {@code fg} -- stays with the
 * interface.
 */
final class JobControl {

    private JobControl() {}

    /**
     * Hands a signal on to the disposition that held it before us.
     *
     * <p>
     * {@code SIG_DFL} and {@code SIG_IGN} name a kernel disposition rather than a callable Java handler, and invoking
     * either throws, so they are passed over: the kernel still applies them. Anything else is a real handler and gets
     * the signal it registered for, inside a catch, because a handler we do not own must not take the resume down with
     * it.
     */
    static void chainTo(sun.misc.SignalHandler prior, sun.misc.Signal sig) {
        if (prior == null || prior == sun.misc.SignalHandler.SIG_DFL || prior == sun.misc.SignalHandler.SIG_IGN)
            return;
        try {
            prior.handle(sig);
        } catch (Throwable ignored) {
            // not ours to fix, and not ours to die from
        }
    }

    /**
     * Deliver SIGTSTP to this process for a real job-control stop. GraalVM's own runtime installs a SIGTSTP dispatcher
     * that intercepts the signal before any {@code sun.misc.Signal} disposition applies (confirmed by native stderr
     * tracing: a Java handler registered for TSTP never runs, and the process never stops). So we bypass the JVM signal
     * machinery entirely and ask the kernel to stop us: a bare {@code kill -TSTP <pid>} via {@code sh}, which needs no
     * registered handler and always takes the default stop-the-process action (the shell prints "Suspended").
     * {@code waitFor()} returns after {@code fg}'s SIGCONT wakes us. No JNA (jnidispatch is not embedded in native
     * images).
     */
    static void raiseSigtstp() throws Exception {
        // No `sh -c`: ProcessBuilder execs the binary off PATH, so the shell builtin never enters it. One fork per
        // Ctrl+Z, and suspend is not a hot path.
        var command = suspendCommand(ParentWatch.supervisor().map(ProcessHandle::pid), ProcessHandle.current().pid());
        int rc = new ProcessBuilder(command).inheritIO().start().waitFor();
        if (rc != 0)
            throw new IllegalStateException("kill -TSTP exited " + rc);
    }

    /**
     * Who has to stop for Ctrl+Z to be a suspend: under a supervisor, both of us.
     *
     * <p>
     * Stopping only ourselves left the supervisor running, and the supervisor is the process the shell is waiting on --
     * so the shell never printed "Suspended", never took the terminal back, and never registered a job there was
     * anything to {@code fg}. What the user got was a frozen screen with their keystrokes echoed raw across it by a tty
     * nobody was reading, and Ctrl+C the only way out, which killed the session instead of resuming it. The native
     * binary never spawns a supervisor, so there the two have always been the same process and this was invisible.
     *
     * <p>
     * The supervisor goes first, so the shell is already reclaiming the terminal by the time we stop. Both pids are
     * named rather than signalling the process group, because {@code kill} itself runs in that group: signalling the
     * group would stop the very process we are waiting on to deliver the signal. Empty means nothing is supervising us
     * and the parent is the user's shell, which must never be stopped.
     */
    static List<String> suspendCommand(Optional<Long> supervisorPid, long selfPid) {
        var command = new ArrayList<>(List.of("kill", "-TSTP"));
        supervisorPid.ifPresent(pid -> command.add(Long.toString(pid)));
        command.add(Long.toString(selfPid));
        return command;
    }
}
