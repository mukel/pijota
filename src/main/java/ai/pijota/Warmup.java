package ai.pijota;

import ai.pijota.tools.ToolRegistry;

/**
 * Overlaps heavy one-time class loading with the rest of startup. Constructing the default tools pulls in ~100 classes
 * (regex, HTTP/TLS, process plumbing); left to the main thread that cost is serialized right after config parsing has
 * already woken Jackson. Triggering it on a background thread hides it under the Jackson cold-start instead.
 * Best-effort: if anything fails the classes simply load lazily later, exactly as before. Pure warm-up — the
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
