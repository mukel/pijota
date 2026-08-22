package org.pijota.browser;

import org.pijota.util.Env;
import org.pijota.util.Executables;
import org.pijota.util.Util;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Builds {@link BiDiBackend}s for specific browsers, resolving the WebDriver binary (geckodriver / safaridriver) and
 * the per-browser launch command + capabilities. Kept separate from {@link BiDiBackend} (which is browser-neutral) so
 * the browser-specific knobs live in one small place.
 */
final class BiDiBackends {

    private BiDiBackends() {}

    static BiDiBackend firefox(int port, String driverOverride, Consumer<String> log, Callable<Boolean> confirm,
                                    int viewportW, int viewportH, List<String> initScripts) {
        return firefox(port, driverOverride, log, confirm, false, viewportW, viewportH, initScripts);
    }

    static BiDiBackend firefox(int port, String driverOverride, Consumer<String> log, Callable<Boolean> confirm,
                                    boolean headless, int viewportW, int viewportH, List<String> initScripts) {
        Path driver = resolveDriver(driverOverride, "pijota.browse.geckodriver", "PIJOTA_GECKODRIVER", "geckodriver");
        List<String> cmd = List.of(driver.toString(), "--port", Integer.toString(port));
        ObjectNode caps = firefoxCapabilities(headless);
        // ponytail: geckodriver manages a fresh profile per session. Passing `-profile <dir>` makes it fail with
        // "Failed to set preferences" (can't write Marionette prefs into an arbitrary dir); a PERSISTENT Firefox
        // profile would need the base64-zipped moz:firefoxOptions.profile capability, a future upgrade.
        return new BiDiBackend(cmd, port, caps, log, headless ? () -> true : confirm, viewportW, viewportH,
                                        initScripts);
    }

    static ObjectNode firefoxCapabilities(boolean headless) {
        ObjectNode caps = Util.MAPPER.createObjectNode();
        caps.put("browserName", "firefox");
        if (headless)
            caps.putObject("moz:firefoxOptions").putArray("args").add("-headless");
        return caps;
    }

    static BiDiBackend safari(int port, String driverOverride, Consumer<String> log, Callable<Boolean> confirm,
                                    int viewportW, int viewportH, List<String> initScripts) {
        Path driver = resolveDriver(driverOverride, "pijota.browse.safaridriver", "PIJOTA_SAFARIDRIVER", "safaridriver",
                                        "/usr/bin/safaridriver");
        List<String> cmd = List.of(driver.toString(), "-p", Integer.toString(port));
        ObjectNode caps = Util.MAPPER.createObjectNode();
        caps.put("browserName", "safari"); // Safari uses the user's default profile; BiDi needs Safari 18+
        return new BiDiBackend(cmd, port, caps, log, confirm, viewportW, viewportH, initScripts);
    }

    /**
     * Resolves a WebDriver binary: explicit override → {@code -DsysProp}/{@code envVar} → {@code PATH} → fixed
     * fallbacks.
     */
    static Path resolveDriver(String override, String sysProp, String envVar, String name, String... extraCandidates) {
        if (override != null && !override.isBlank())
            return Path.of(override);
        String configured = Env.get(sysProp, envVar);
        if (configured != null)
            return Path.of(configured);
        var candidates = new ArrayList<String>();
        candidates.add(name); // resolved on PATH (cross-OS, PATHEXT-aware)
        candidates.addAll(List.of(extraCandidates));
        Path found = Executables.findAny(candidates);
        if (found == null)
            throw new IllegalStateException(name + " not found. Install it or set " + envVar + " / -D" + sysProp
                                            + "=/path/to/" + name + ".");
        return found;
    }
}
