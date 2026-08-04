package org.pijota.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ProjectTrust {
    /**
     * Everything under a project's {@code .pijota/} that trust gates. Keep this in step with what actually loads: a
     * name missing here means the folder carries something and the user is never told, which is how {@code mcp.json}
     * came to launch a repository's chosen command with no notice at all.
     */
    private static final List<String> PROTECTED = List.of("settings.json", "extensions", "skills", "prompts", "themes",
                                    "agents", "mcp.json", "SYSTEM.md", "APPEND_SYSTEM.md");

    /**
     * The answer this run resolved for the working directory, which is not always what the store says: {@code -na}
     * refuses a folder the store trusts, and {@code -a} accepts one it does not. Anything re-deriving trust from the
     * store alone silently ignores both flags, so the decision is made once and read from here.
     */
    private static volatile String resolvedCwd;
    private static volatile boolean resolvedTrust;

    private ProjectTrust() {}

    /** Records the run's decision. Called by the bootstrap once, and by {@code /trust} when the user changes it. */
    public static void resolve(String cwd, boolean trusted) {
        resolvedCwd = cwd;
        resolvedTrust = trusted;
    }

    public static boolean isTrusted(String cwd) {
        if (cwd == null || cwd.isBlank())
            return false;
        if (cwd.equals(resolvedCwd))
            return resolvedTrust;
        return new TrustStore().isTrusted(cwd);
    }

    public static boolean hasProtectedResources(String cwd) {
        if (cwd == null || cwd.isBlank())
            return false;
        Path root = Path.of(cwd, ".pijota");
        for (String name : PROTECTED)
            if (Files.exists(root.resolve(name)))
                return true;
        return false;
    }
}
