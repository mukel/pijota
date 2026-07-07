package ai.pijota.extensions.testkit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class ExtensionJars {
    private ExtensionJars() {}

    public static Path serviceLoaderJar(Path dir, String providerClass) throws Exception {
        Path jar = dir.resolve("extension.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/services/ai.pijota.extensions.Extension"));
            out.write(providerClass.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
