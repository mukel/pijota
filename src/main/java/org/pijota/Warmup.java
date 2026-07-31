package org.pijota;

import org.pijota.tools.ToolRegistry;

/**
 * Overlaps heavy one-time class loading with the rest of startup. Constructing the default tools pulls in ~100 classes
 * (regex, HTTP/TLS, process plumbing); doing it on a background thread hides that cost under the Jackson cold-start
 * instead of serializing it on the main thread. Best-effort; on failure the classes just load lazily later. The
 * constructed registry is discarded and has no side effects (tool constructors do no I/O).
 */
final class Warmup {
    private Warmup() {}

    static void start() {
        Thread.ofPlatform().daemon().name("pijota-warmup").start(() -> {
            try {
                ToolRegistry.createDefault();
            } catch (Throwable ignored) {
                // warm-up is advisory; lazy class loading remains the fallback
            }
        });
    }
}
