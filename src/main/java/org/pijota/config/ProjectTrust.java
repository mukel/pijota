package org.pijota.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ProjectTrust {
    private static final List<String> PROTECTED = List.of("settings.json", "extensions", "skills", "prompts", "themes",
                                    "SYSTEM.md", "APPEND_SYSTEM.md");

    private ProjectTrust() {}

    public static boolean isTrusted(String cwd) {
        return new TrustStore().isTrusted(cwd);
    }

    public static boolean hasProtectedResources(String cwd) {
        if (cwd == null || cwd.isBlank())
            return false;
        Path root = Path.of(cwd, ".pijota");
        for (String name : PROTECTED)
            if (Files.exists(root.resolve(name)))
                return true;
        Path piRoot = Path.of(cwd, ".pi");
        for (String name : PROTECTED)
            if (Files.exists(piRoot.resolve(name)))
                return true;
        return false;
    }
}
