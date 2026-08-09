package org.pijota.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Exits this process when the supervisor that started it goes away.
 *
 * <p>
 * The supervisor destroys its worker from a shutdown hook, which covers every signal a JVM can see. It cannot cover
 * {@code SIGKILL}, which is not deliverable to a handler: the supervisor simply stops, and the worker is reparented to
 * init and keeps running. Measured, that is where a week-old {@code --worker web} process serving HTTP 200 comes from,
 * with nothing left that knows it exists. Watching from this side is the only thing that closes it.
 *
 * <p>
 * The watch is on a {@link ProcessHandle}, which identifies one process instance rather than a number, so a recycled
 * pid can never be mistaken for the supervisor still being alive -- or worse, for it having died.
 */
public final class ParentWatch {
    private static final Logger log = LoggerFactory.getLogger(ParentWatch.class);

    /** Exit code when the supervisor vanished. Distinct so it is recognisable in a log or a wrapper script. */
    public static final int EXIT_PARENT_GONE = 137;

    /** The supervisor that started this worker, or null when nothing is supervising us. Set by {@link #arm}. */
    private static volatile ProcessHandle supervisor;

    private ParentWatch() {}

    /** Arms the watch if this process should die with its parent; returns whether it did. */
    public static boolean arm(boolean worker) {
        // Checked before touching ProcessHandle: resolving the parent costs a couple of milliseconds of class init
        // the first time, and every headless run, every sub-agent and every native launch would pay it to learn it
        // is not a worker.
        if (!worker)
            return false;
        var parent = ProcessHandle.current().parent();
        supervisor = parent.orElse(null);
        return arm(true, parent, () -> System.exit(EXIT_PARENT_GONE));
    }

    /**
     * The supervisor that started this worker, empty for anything not launched by one -- a native run, a headless run,
     * a sub-agent.
     *
     * <p>
     * Two things need to know who the supervisor is, and both are about not outliving or outrunning it: this class,
     * which exits when it dies, and Ctrl+Z, which has to stop it as well or the shell keeps waiting on a parent that
     * never stopped. Empty is the answer that means "there is only me", so a caller that gets it acts on itself alone
     * -- which is exactly right when the parent is the user's shell.
     */
    public static Optional<ProcessHandle> supervisor() {
        return Optional.ofNullable(supervisor);
    }

    /**
     * Seam for tests: takes the parent handle and what to do when it exits.
     *
     * <p>
     * Only a worker is watched. Anything else was started from a shell, and a shell exiting is not a reason to stop.
     */
    static boolean arm(boolean worker, Optional<ProcessHandle> parent, Runnable onParentExit) {
        if (!worker || parent.isEmpty())
            return false;
        parent.get().onExit().thenRun(() -> {
            log.warn("Supervisor process {} exited; shutting down rather than outliving it", parent.get().pid());
            onParentExit.run();
        });
        return true;
    }
}
