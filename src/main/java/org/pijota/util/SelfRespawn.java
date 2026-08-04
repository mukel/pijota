package org.pijota.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SelfRespawn {
    private static final Logger logger = LoggerFactory.getLogger(SelfRespawn.class);

    static final Path BACKUP_JAR = Path.of("target", "pijota-backup.jar");
    static final Path RECOVERY_FILE = Path.of("target", ".transcend-session");
    static final Path RESTART_FILE = Path.of("target", ".transcend-restart");
    static final Path STAGED_JAR = Path.of("target", "pijota-transcend.jar");
    // Boot-health sentinel: the supervisor creates it before launching a freshly-respawned build; the worker
    // deletes it once it has started cleanly. If it survives the worker, the new build never came up → roll back.
    static final Path BOOTING_FILE = Path.of("target", ".transcend-booting");

    private static volatile Path activeSessionFile;
    private static volatile String[] originalArgs;

    private static final AtomicBoolean pending = new AtomicBoolean(false);
    private static final AtomicBoolean respawnInProgress = new AtomicBoolean(false);
    private static final AtomicReference<Path> pendingJar = new AtomicReference<>();
    private static final AtomicReference<String> pendingMergeBranch = new AtomicReference<>();
    private static final AtomicReference<Path> pendingMergeWorktree = new AtomicReference<>();

    private SelfRespawn() {}

    public static void setSessionFile(Path file) {
        activeSessionFile = file;
    }

    public static void storeArgs(String[] args) {
        originalArgs = args.clone();
    }

    public static boolean requestRespawn(Path jar) {
        return requestRespawn(jar, null, null);
    }

    public static boolean requestRespawn(Path jar, String mergeBranch, Path mergeWorktree) {
        if (jar == null || !Files.exists(jar)) {
            logger.warn("requestRespawn: jar not found{} · build first: mvn package -Pfat -q",
                                            jar != null ? " at " + jar : "");
            return false;
        }
        if (pending.get()) {
            logger.warn("respawn already pending · skipping duplicate request");
            return false;
        }
        Path launchJar;
        try {
            launchJar = stageLaunchJar(jar, mergeWorktree);
        } catch (IOException e) {
            logger.warn("failed to stage respawn jar: {}", e.getMessage());
            return false;
        }
        pendingJar.set(launchJar);
        pendingMergeBranch.set(blankToNull(mergeBranch));
        pendingMergeWorktree.set(mergeWorktree);
        pending.set(true);
        writeRecoveryFile();
        return true;
    }

    public static boolean canRecover() {
        return Files.exists(RECOVERY_FILE) && size(RECOVERY_FILE) > 0;
    }

    private static Path stageLaunchJar(Path jar, Path mergeWorktree) throws IOException {
        Path normalizedJar = jar.toAbsolutePath().normalize();
        if (mergeWorktree == null)
            return normalizedJar;

        Path worktree = mergeWorktree.toAbsolutePath().normalize();
        if (!normalizedJar.startsWith(worktree))
            return normalizedJar;

        Path parent = STAGED_JAR.getParent();
        if (parent != null)
            Files.createDirectories(parent);
        Files.copy(normalizedJar, STAGED_JAR, StandardCopyOption.REPLACE_EXISTING);
        return STAGED_JAR.toAbsolutePath().normalize();
    }

    public static Path readRecoverySession() {
        try {
            if (!Files.exists(RECOVERY_FILE))
                return null;
            String content = Files.readString(RECOVERY_FILE).strip();
            if (content.isBlank())
                return null;
            Path path = Path.of(content);
            return Files.exists(path) ? path : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeRecoveryFile() {
        Path session = activeSessionFile;
        if (session == null)
            return;
        try {
            Path parent = RECOVERY_FILE.getParent();
            if (parent != null)
                Files.createDirectories(parent);
            Files.writeString(RECOVERY_FILE, session.toAbsolutePath().toString());
        } catch (IOException ignored) {}
    }

    public static void clearRecoveryFile() {
        try {
            Files.deleteIfExists(RECOVERY_FILE);
        } catch (IOException ignored) {}
    }

    public static boolean isPending() {
        return pending.get() && !respawnInProgress.get();
    }

    private static volatile String lastRespawnError;

    public static String lastRespawnError() {
        return lastRespawnError;
    }

    public static void maybeRespawn() {
        lastRespawnError = null;
        if (!pending.get() || respawnInProgress.get())
            return;

        Path jar = pendingJar.getAndSet(null);
        pending.set(false);

        if (jar == null || !Files.exists(jar)) {
            jar = findJarIn(Path.of(System.getProperty("user.dir")));
            if (jar == null || !Files.exists(jar)) {
                lastRespawnError = "no jar found for respawn · rebuild with: mvn package -Pfat -q";
                logger.warn(lastRespawnError);
                return;
            }
        }

        Path session = activeSessionFile;
        String mergeBranch = pendingMergeBranch.getAndSet(null);
        Path mergeWorktree = pendingMergeWorktree.getAndSet(null);
        executeRespawn(jar, session, mergeBranch, mergeWorktree);
    }

    private static void executeRespawn(Path jar, Path sessionFile, String mergeBranch, Path mergeWorktree) {
        if (!respawnInProgress.compareAndSet(false, true))
            return;
        try {
            respawn(jar, sessionFile, true, mergeBranch, mergeWorktree);
        } finally {
            respawnInProgress.set(false);
        }
    }

    public static String findGitRoot(String startDir) {
        Path dir = Path.of(startDir).toAbsolutePath().normalize();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve(".git")))
                return dir.toString();
            dir = dir.getParent();
        }
        return startDir;
    }

    public static Path findCurrentJar() {
        String cp = System.getProperty("java.class.path");
        if (cp != null) {
            for (String entry : cp.split(System.getProperty("path.separator", ":"))) {
                if (entry.endsWith(".jar") && entry.contains("pijota") && !entry.contains("backup"))
                    return Path.of(entry);
            }
        }
        return findJarIn(Path.of(System.getProperty("user.dir")));
    }

    public static Path findJarIn(Path projectDir) {
        if (projectDir == null)
            return null;
        Path target = projectDir.resolve("target");
        if (!Files.isDirectory(target))
            return null;
        try (var s = Files.list(target)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("pijota-") && n.endsWith(".jar") && !n.contains("original") && !n.contains("backup")
                                                && !n.contains("sources") && !n.contains("javadoc");
            }).max((a, b) -> {
                try {
                    return Long.compare(Files.getLastModifiedTime(a).toMillis(),
                                                    Files.getLastModifiedTime(b).toMillis());
                } catch (IOException e) {
                    return 0;
                }
            }).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean backupJar(Path jar) throws IOException {
        if (jar == null || !Files.exists(jar))
            return false;
        Path parent = BACKUP_JAR.getParent();
        if (parent != null)
            Files.createDirectories(parent);
        Files.copy(jar, BACKUP_JAR, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    public static boolean canRollback() {
        return Files.exists(BACKUP_JAR) && size(BACKUP_JAR) > 0;
    }

    public static void rollback(Path targetJar) throws IOException {
        if (!Files.exists(BACKUP_JAR))
            throw new IOException("No backup jar at " + BACKUP_JAR);
        if (size(BACKUP_JAR) <= 0)
            throw new IOException("Backup jar is empty at " + BACKUP_JAR);
        if (targetJar == null)
            throw new IOException("No target jar to restore to");
        Path parent = targetJar.getParent();
        if (parent != null)
            Files.createDirectories(parent);
        Files.copy(BACKUP_JAR, targetJar, StandardCopyOption.REPLACE_EXISTING);
    }

    public static ProcessRunner.Result buildJar(Path projectRoot) throws IOException {
        if (projectRoot == null || !Files.exists(projectRoot.resolve("pom.xml")))
            throw new IOException("No pom.xml in " + projectRoot);
        return ProcessRunner.command("mvn", "package", "-Pfat", "-q").workDir(projectRoot).redirectErrorStream(true)
                                        .run();
    }

    public static int runInteractive(Path jar, Path sessionFile, boolean resumeSession) throws IOException {
        if (jar == null || !Files.exists(jar))
            throw new IOException("Jar not found: " + jar);
        List<String> cmd = buildCommand(jar, sessionFile, resumeSession);
        return ProcessRunner.command(cmd).inheritIO().runInteractive();
    }

    public static void respawn(Path jar, Path sessionFile, boolean resumeSession) {
        respawn(jar, sessionFile, resumeSession, null, null);
    }

    public static void respawn(Path jar, Path sessionFile, boolean resumeSession, String mergeBranch,
                                    Path mergeWorktree) {
        if (jar == null || !Files.exists(jar)) {
            lastRespawnError = "jar not found" + (jar != null ? " at " + jar : "") + " · rebuild: mvn package -Pfat -q";
            logger.warn("respawn aborted: {}", lastRespawnError);
            return;
        }
        List<String> cmd = buildCommand(jar, sessionFile, resumeSession, mergeBranch, mergeWorktree);
        respawnInProgress.set(true);
        try {
            writeRestartRequest(cmd);
            clearRecoveryFile();
            System.exit(0);
        } catch (IOException e) {
            respawnInProgress.set(false);
            lastRespawnError = "respawn failed: " + e.getMessage();
            logger.warn(lastRespawnError);
        }
    }

    public static boolean isRespawnInProgress() {
        return respawnInProgress.get();
    }

    // ---- boot-health handshake (supervisor auto-rollback safety net) ----

    /** Arm the boot-health check before launching a freshly-respawned build. */
    public static void markBooting() {
        try {
            Path parent = BOOTING_FILE.getParent();
            if (parent != null)
                Files.createDirectories(parent);
            Files.writeString(BOOTING_FILE, "booting");
        } catch (IOException ignored) {}
    }

    /** Signal a healthy startup (the worker calls this once bootstrap succeeds). No-op for non-respawn launches. */
    public static void markStartupHealthy() {
        try {
            Files.deleteIfExists(BOOTING_FILE);
        } catch (IOException ignored) {}
    }

    public static boolean isBooting() {
        return Files.exists(BOOTING_FILE);
    }

    /**
     * Builds the command to relaunch the previous (backup) build after a respawned build failed to start: restores the
     * backup jar over the failed command's jar and drops the transcend merge/worktree flags (the bad build is being
     * abandoned). The session arg is preserved, so the conversation survives. Empty if there's no backup, no jar in the
     * command, or the restore fails.
     */
    public static Optional<List<String>> autoRollbackCommand(List<String> failedCommand) {
        if (!canRollback() || failedCommand == null)
            return Optional.empty();
        int jarIdx = failedCommand.indexOf("-jar");
        if (jarIdx < 0 || jarIdx + 1 >= failedCommand.size())
            return Optional.empty();
        try {
            rollback(Path.of(failedCommand.get(jarIdx + 1))); // overwrite the failed jar with the known-good backup
        } catch (IOException e) {
            logger.warn("auto-rollback failed: {}", e.getMessage());
            return Optional.empty();
        }
        return Optional.of(stripTranscendFlags(failedCommand));
    }

    /** Removes the {@code --transcend-merge-branch}/{@code --transcend-worktree} flags (and their values). */
    static List<String> stripTranscendFlags(List<String> command) {
        var out = new ArrayList<String>(command.size());
        for (int i = 0; i < command.size(); i++) {
            String a = command.get(i);
            if (a.equals("--transcend-merge-branch") || a.equals("--transcend-worktree")) {
                if (i + 1 < command.size())
                    i++; // skip the flag's value too
                continue;
            }
            out.add(a);
        }
        return out;
    }

    public static Optional<List<String>> readRestartRequest() {
        if (!Files.exists(RESTART_FILE))
            return Optional.empty();
        var properties = new Properties();
        try (var in = Files.newInputStream(RESTART_FILE)) {
            properties.load(in);
            int count = Integer.parseInt(properties.getProperty("argc", "0"));
            if (count <= 0)
                return Optional.empty();
            var command = new ArrayList<String>();
            for (int i = 0; i < count; i++) {
                String arg = properties.getProperty("arg." + i);
                if (arg == null)
                    return Optional.empty();
                command.add(arg);
            }
            return Optional.of(command);
        } catch (Exception e) {
            logger.warn("failed to read restart request: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public static void clearRestartRequest() {
        try {
            Files.deleteIfExists(RESTART_FILE);
        } catch (IOException ignored) {}
    }

    private static void writeRestartRequest(List<String> command) throws IOException {
        Path parent = RESTART_FILE.getParent();
        if (parent != null)
            Files.createDirectories(parent);

        var properties = new Properties();
        properties.setProperty("argc", Integer.toString(command.size()));
        for (int i = 0; i < command.size(); i++) {
            properties.setProperty("arg." + i, command.get(i));
        }

        Path tmp = RESTART_FILE.resolveSibling(RESTART_FILE.getFileName() + ".tmp");
        try (var out = Files.newOutputStream(tmp)) {
            properties.store(out, "pijota transcend restart request");
        }
        Util.replaceFile(tmp, RESTART_FILE);
    }

    public static ProcessRunner.Result mergeBranch(String branch) throws IOException {
        if (branch == null || branch.isBlank())
            throw new IOException("No branch specified");
        return ProcessRunner.command("git", "merge", branch).redirectErrorStream(true).run();
    }

    public static String backupStatus() {
        if (!Files.exists(BACKUP_JAR))
            return "no backup";
        try {
            long sz = Files.size(BACKUP_JAR);
            return sz <= 0 ? "empty" : (sz / 1024 + "KB");
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static List<String> buildCommand(Path jar, Path sessionFile, boolean resumeSession) {
        return buildCommand(jar, sessionFile, resumeSession, null, null);
    }

    private static List<String> buildCommand(Path jar, Path sessionFile, boolean resumeSession, String mergeBranch,
                                    Path mergeWorktree) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveJava());
        cmd.addAll(jvmOptions());
        cmd.add("-jar");
        cmd.add(jar.toAbsolutePath().toString());
        cmd.add("--worker");

        if (sessionFile != null && Files.exists(sessionFile)) {
            cmd.add("--session-path");
            cmd.add(sessionFile.toAbsolutePath().toString());
        }

        if (resumeSession && sessionFile == null) {
            cmd.add("--continue");
        }

        String branch = blankToNull(mergeBranch);
        if (branch != null) {
            cmd.add("--transcend-merge-branch");
            cmd.add(branch);
        }
        if (mergeWorktree != null) {
            cmd.add("--transcend-worktree");
            cmd.add(mergeWorktree.toAbsolutePath().normalize().toString());
        }

        cmd.addAll(preservedArgs());
        return cmd;
    }

    /**
     * The {@code -D} options this JVM was started with, so a child inherits them.
     *
     * <p>
     * Child commands are rebuilt from scratch rather than forwarded, which silently dropped every system property:
     * {@code -Dpijota.home=/somewhere} put the banner, the settings and the model where it was told, and the worker
     * that did the actual work read {@code ~/.pijota} instead. Trust decisions in particular were written to the real
     * home from a session the user had pointed elsewhere. Environment variables survived, so {@code PIJOTA_HOME} worked
     * and the documented property did not, which is the worse of the two to debug.
     *
     * <p>
     * Only {@code -D} is carried: heap sizing and agent flags belong to the launcher's JVM, and a native image reports
     * no input arguments at all.
     */
    public static List<String> jvmOptions() {
        // A native image has no JVM options to inherit, and asking for them there can fail as an Error rather than an
        // Exception, which would take startup down with it.
        if (Platform.isNativeImage())
            return List.of();
        try {
            return ManagementFactory.getRuntimeMXBean().getInputArguments().stream().filter(a -> a.startsWith("-D"))
                                            .toList();
        } catch (Throwable t) {
            return List.of();
        }
    }

    public static String resolveJava() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path javaBin = Path.of(javaHome, "bin", "java");
            if (Files.exists(javaBin))
                return javaBin.toAbsolutePath().toString();
            javaBin = Path.of(javaHome, "bin", "java.exe");
            if (Files.exists(javaBin))
                return javaBin.toAbsolutePath().toString();
        }
        String cmd = ProcessHandle.current().info().command().orElse("");
        if (!cmd.isBlank())
            return cmd;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "java.exe" : "java";
    }

    static List<String> preservedArgs() {
        List<String> out = new ArrayList<>();
        String[] orig = originalArgs;
        if (orig == null)
            return out;

        for (int i = 0; i < orig.length; i++) {
            String a = orig[i];

            if (isSkippedFlag(a)) {
                if (i + 1 < orig.length && isValue(orig[i + 1]))
                    i++;
                continue;
            }

            if (a.startsWith("-")) {
                out.add(a);
                if (i + 1 < orig.length && isValue(orig[i + 1]))
                    out.add(orig[++i]);
            }
        }
        return out;
    }

    private static boolean isSkippedFlag(String a) {
        return a.equals("--session") || a.equals("--session-path") || a.equals("--resume") || a.equals("--continue")
                                        || a.equals("--recover") || a.equals("--fork") || a.equals("--export")
                                        || a.equals("--no-session") || a.equals("--agent") || a.equals("--print")
                                        || a.equals("--mode") || a.equals("--worker")
                                        || a.equals("--transcend-merge-branch") || a.equals("--transcend-worktree")
                                        || a.equals("--output-mode") || a.equals("--help") || a.equals("--version")
                                        || a.equals("--list-models") || a.equals("-s") || a.equals("-r")
                                        || a.equals("-c") || a.equals("-p") || a.equals("-h") || a.equals("-v");
    }

    private static String blankToNull(String value) {
        if (value == null)
            return null;
        String stripped = value.strip();
        return stripped.isBlank() ? null : stripped;
    }

    private static boolean isValue(String a) {
        return !a.startsWith("--");
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }
}
