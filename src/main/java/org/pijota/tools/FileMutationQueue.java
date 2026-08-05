package org.pijota.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes mutations to one file, keyed by path.
 *
 * <p>
 * Two tools editing the same file in one batch would otherwise interleave read-modify-write and lose an edit; the lock
 * is per path, so unrelated files still proceed in parallel.
 */
public final class FileMutationQueue {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private FileMutationQueue() {}

    public static <T> T run(Path path, Callable<T> action) throws Exception {
        Path key = key(path);
        ReentrantLock lock = LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.call();
        } finally {
            lock.unlock();
        }
    }

    private static Path key(Path path) throws Exception {
        Path absolute = path.toAbsolutePath().normalize();
        return Files.exists(absolute) ? absolute.toRealPath() : absolute;
    }
}
