package org.pijota.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FileMutationQueueTest {

    @Test
    void runExecutesActionAndReturnsResult(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.txt");
        Files.writeString(file, "content");

        var result = FileMutationQueue.run(file, () -> "success");
        assertEquals("success", result);
    }

    @Test
    void runOnNonexistentPathStillWorks(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("nonexistent.txt");
        var result = FileMutationQueue.run(file, () -> 42);
        assertEquals(42, result);
    }

    @Test
    void runSerializesAccessToSameFile(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("shared.txt");
        Files.writeString(file, "initial");

        var order = new ArrayList<Integer>();
        var latch = new CountDownLatch(1);

        var t1 = new Thread(() -> {
            try {
                FileMutationQueue.run(file, () -> {
                    order.add(1);
                    latch.countDown();
                    Thread.sleep(50);
                    order.add(2);
                    return null;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        var t2 = new Thread(() -> {
            try {
                latch.await();
                FileMutationQueue.run(file, () -> {
                    order.add(3);
                    return null;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertEquals(List.of(1, 2, 3), order);
    }

    @Test
    void runOnDifferentFilesAllowsConcurrency(@TempDir Path tmp) throws Exception {
        var fileA = tmp.resolve("a.txt");
        var fileB = tmp.resolve("b.txt");
        Files.writeString(fileA, "a");
        Files.writeString(fileB, "b");

        var ran = new AtomicBoolean(false);
        var latch = new CountDownLatch(1);

        var t1 = new Thread(() -> {
            try {
                FileMutationQueue.run(fileA, () -> {
                    latch.countDown();
                    Thread.sleep(200);
                    return null;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        t1.start();
        latch.await();
        FileMutationQueue.run(fileB, () -> {
            ran.set(true);
            return null;
        });
        t1.join();

        assertTrue(ran.get());
    }

    @Test
    void runPropagatesException(@TempDir Path tmp) {
        var file = tmp.resolve("test.txt");
        assertThrows(RuntimeException.class, () -> {
            FileMutationQueue.run(file, () -> {
                throw new RuntimeException("boom");
            });
        });
    }

    @Test
    void runUnlocksEvenWhenThrowing(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.txt");
        Files.writeString(file, "content");

        try {
            FileMutationQueue.run(file, () -> {
                throw new RuntimeException("fail");
            });
        } catch (RuntimeException ignored) {}

        var result = FileMutationQueue.run(file, () -> "recovered");
        assertEquals("recovered", result);
    }
}
