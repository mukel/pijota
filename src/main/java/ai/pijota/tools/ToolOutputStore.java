package ai.pijota.tools;

import ai.pijota.util.PijotaHome;
import ai.pijota.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolOutputStore {
    public static final int DEFAULT_MAX_CHARS = 100_000;
    private static final Duration RETENTION = Duration.ofDays(7);

    private ToolOutputStore() {}

    public record Stored(String text, Map<String, Object> details) {
    }

    public static Path save(String text, String prefix) throws IOException {
        cleanupBestEffort();
        Path dir = PijotaHome.resolve("tool-output");
        Files.createDirectories(dir);
        Path file = Files.createTempFile(dir, cleanPrefix(prefix), ".txt");
        Files.writeString(file, text == null ? "" : text);
        return file;
    }

    public static Stored bound(String text, int maxChars, String prefix) throws IOException {
        if (text == null)
            text = "";
        if (text.length() <= maxChars)
            return new Stored(text, Map.of());
        Path file = save(text, prefix);
        var details = new LinkedHashMap<String, Object>();
        details.put("truncated", true);
        details.put("outputPath", file.toString());
        details.put("fullOutputPath", file.toString());
        details.put("maxChars", maxChars);
        return new Stored(Util.truncateHeadTail(text, maxChars, file.toString()), Map.copyOf(details));
    }

    private static String cleanPrefix(String prefix) {
        String p = prefix == null || prefix.isBlank() ? "tool-" : prefix;
        p = p.replaceAll("[^A-Za-z0-9._-]", "_");
        return p.length() >= 3 ? p : "tool-";
    }

    private static void cleanupBestEffort() {
        Path dir = PijotaHome.resolve("tool-output");
        long cutoff = System.currentTimeMillis() - RETENTION.toMillis();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                try {
                    if (Files.getLastModifiedTime(file).toMillis() < cutoff)
                        Files.deleteIfExists(file);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
