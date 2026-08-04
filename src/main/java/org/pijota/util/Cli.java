package org.pijota.util;

import java.nio.file.Path;

/**
 * The name pijota was invoked as, for messages that tell the user a command to run.
 *
 * <p>
 * The binary is installed as {@code pj}: that is what {@code jbang app install --name pj} produces, what
 * {@code make native} writes, and what the README documents. Every message that named a command said {@code pijota}
 * instead, so a new user read {@code `pijota --list-models`} off their first screen and got "command not found".
 *
 * <p>
 * Only for commands the user is meant to type. Where the text names the program rather than a command ("pijota v0.2.0",
 * "pijota: ignoring unknown option"), the product name is correct and stays.
 */
public final class Cli {

    /** What the documented install paths produce, and the answer whenever the real name cannot be read. */
    private static final String DEFAULT = "pj";

    private Cli() {}

    /**
     * The executable's own name in a native image, where it is exactly what the user typed; {@code pj} otherwise.
     *
     * <p>
     * On the JVM the current process is {@code java}, which is never what to suggest, and the launcher (jbang, a
     * wrapper script) is not visible from here. The native binary is the release artifact, so the case that can be
     * answered honestly is the one that ships.
     */
    public static String name() {
        if (!Platform.isNativeImage())
            return DEFAULT;
        return ProcessHandle.current().info().command().map(c -> Path.of(c).getFileName().toString())
                                        .filter(n -> !n.isBlank()).orElse(DEFAULT);
    }
}
