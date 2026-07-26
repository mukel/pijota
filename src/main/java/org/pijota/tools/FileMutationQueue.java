package org.pijota.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class FileMutationQueue {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private FileMutationQueue() {}

    public static <T> T run(Path path, Callable<T> action) throws Exception {
        Path key = key(path);
        ReentrantLock lock = LOCKS.computeIfAbsent(key, _ -> new ReentrantLock());
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
