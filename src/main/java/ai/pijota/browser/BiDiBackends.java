package ai.pijota.browser;

import ai.pijota.util.Util;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Builds {@link BiDiBackend}s for specific browsers — resolves the WebDriver binary (geckodriver / safaridriver) and
 * the per-browser launch command + capabilities. Kept separate from {@link BiDiBackend} (which is browser-neutral) so
 * the browser-specific knobs live in one small place.
 */
final class BiDiBackends {

    private BiDiBackends() {}

    static BiDiBackend firefox(int port, String driverOverride, Consumer<String> log, Callable<Boolean> confirm,
                                    int viewportW, int viewportH, List<String> initScripts) {
        Path driver = resolveDriver(driverOverride, "PIJOTA_GECKODRIVER", "geckodriver");
        List<String> cmd = List.of(driver.toString(), "--port", Integer.toString(port));
        ObjectNode caps = Util.MAPPER.createObjectNode();
        caps.put("browserName", "firefox");
        // ponytail: geckodriver manages a fresh profile per session. Passing `-profile <dir>` makes it fail with
        // "Failed to set preferences" (can't write Marionette prefs into an arbitrary dir); a PERSISTENT Firefox
        // profile would need the base64-zipped moz:firefoxOptions.profile capability — a future upgrade.
        return new BiDiBackend(cmd, port, caps, log, confirm, viewportW, viewportH, initScripts);
    }

    static BiDiBackend safari(int port, String driverOverride, Consumer<String> log, Callable<Boolean> confirm,
                                    int viewportW, int viewportH, List<String> initScripts) {
        Path driver = resolveDriver(driverOverride, "PIJOTA_SAFARIDRIVER", "safaridriver", "/usr/bin/safaridriver");
        List<String> cmd = List.of(driver.toString(), "-p", Integer.toString(port));
        ObjectNode caps = Util.MAPPER.createObjectNode();
        caps.put("browserName", "safari"); // Safari uses the user's default profile; BiDi needs Safari 18+
        return new BiDiBackend(cmd, port, caps, log, confirm, viewportW, viewportH, initScripts);
    }

    /** Resolves a WebDriver binary: explicit override → {@code envVar} → {@code PATH} → fixed fallbacks. */
    static Path resolveDriver(String override, String envVar, String name, String... extraCandidates) {
        if (override != null && !override.isBlank())
            return Path.of(override);
        String env = System.getenv(envVar);
        if (env != null && !env.isBlank())
            return Path.of(env);
        var candidates = new ArrayList<Path>();
        String path = System.getenv("PATH");
        if (path != null)
            for (String dir : path.split(File.pathSeparator))
                candidates.add(Path.of(dir, name));
        for (String c : extraCandidates)
            candidates.add(Path.of(c));
        Path found = CdpBackend.firstExecutable(candidates, Files::isExecutable);
        if (found == null)
            throw new IllegalStateException(name + " not found. Install it or set " + envVar + "=/path/to/" + name
                                            + ".");
        return found;
    }
}
