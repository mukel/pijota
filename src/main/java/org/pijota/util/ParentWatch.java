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

    private ParentWatch() {}

    /** Arms the watch if this process should die with its parent; returns whether it did. */
    public static boolean arm(boolean worker) {
        return arm(worker, ProcessHandle.current().parent(), () -> System.exit(EXIT_PARENT_GONE));
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
