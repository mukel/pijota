package org.pijota.config;

import org.pijota.util.PijotaHome;
import org.pijota.util.Util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Remembers which project directories the user has trusted. A trusted folder may load its own context files
 * ({@code AGENTS.md}, {@code CLAUDE.md}, {@code .pijota/CONTEXT.md}) into the agent's prompt — a cloned, untrusted repo
 * could otherwise inject instructions via those files. Trust is inherited by subdirectories. Persisted to
 * {@code ~/.pijota/trust.json} (owner-only).
 */
public final class TrustStore {

    private final Path file;
    private final Set<String> trusted = new LinkedHashSet<>();

    public TrustStore() {
        this(PijotaHome.resolve("trust.json"));
    }

    public TrustStore(Path file) {
        this.file = file;
        load();
    }

    private synchronized void load() {
        try {
            if (Files.exists(file)) {
                var data = Util.MAPPER.readValue(file.toFile(), TrustData.class);
                if (data != null && data.trusted() != null)
                    trusted.addAll(data.trusted());
            }
        } catch (Exception ignored) {
            // a corrupt/unreadable store just means nothing is trusted yet — fail closed
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Util.restrictToOwner(file.getParent());
            // Atomic write (tmp + rename): a crash mid-write must never truncate trust.json.
            String json = Util.MAPPER.writerWithDefaultPrettyPrinter()
                                            .writeValueAsString(new TrustData(new ArrayList<>(trusted)));
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            Util.restrictToOwner(tmp);
            Util.replaceFile(tmp, file);
            Util.restrictToOwner(file);
        } catch (Exception ignored) {}
    }

    private static String normalize(String dir) {
        try {
            return Path.of(dir).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return dir;
        }
    }

    /** True if {@code dir} is itself trusted or sits under a trusted ancestor. */
    public synchronized boolean isTrusted(String dir) {
        if (dir == null || dir.isBlank())
            return false;
        Path p = Path.of(normalize(dir));
        for (String t : trusted) {
            try {
                if (p.startsWith(Path.of(t)))
                    return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    public synchronized void trust(String dir) {
        trusted.add(normalize(dir));
        save();
    }

    /** Remove an exact trusted entry (a still-trusted ancestor would keep subdirectories trusted). */
    public synchronized void revoke(String dir) {
        if (trusted.remove(normalize(dir)))
            save();
    }

    public synchronized List<String> trustedDirs() {
        return new ArrayList<>(trusted);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrustData(List<String> trusted) {
    }
}
