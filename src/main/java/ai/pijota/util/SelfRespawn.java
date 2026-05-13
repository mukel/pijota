package ai.pijota.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SelfRespawn {

    static final Path BACKUP_JAR = Path.of("target", "pijota-backup.jar");
    static final String LOCK_PREFIX = "[transcend] ";

    private static volatile Path activeSessionFile;
    private static volatile String[] originalArgs;

    private static final AtomicBoolean pending = new AtomicBoolean(false);
    private static final AtomicBoolean respawnInProgress = new AtomicBoolean(false);
    private static final AtomicReference<Path> pendingJar = new AtomicReference<>();
    private static final AtomicReference<Thread> shutdownHook = new AtomicReference<>();

    private SelfRespawn() {}

    public static void setSessionFile(Path file) { activeSessionFile = file; }

    public static void storeArgs(String[] args) { originalArgs = args.clone(); }

    public static boolean requestRespawn(Path jar) {
        if (jar == null || !Files.exists(jar)) {
            System.err.println(LOCK_PREFIX + "requestRespawn: jar is null or missing");
            return false;
        }
        if (pending.get()) {
            System.err.println(LOCK_PREFIX + "respawn already pending — skipping duplicate request");
            return false;
        }
        pendingJar.set(jar);
        pending.set(true);
        return true;
    }

    public static boolean isPending() {
        return pending.get() && !respawnInProgress.get();
    }

    public static void maybeRespawn() {
        if (!pending.get() || respawnInProgress.get()) return;

        Path jar = pendingJar.getAndSet(null);
        pending.set(false);

        if (jar == null || !Files.exists(jar)) {
            jar = findJarIn(Path.of(System.getProperty("user.dir")));
            if (jar == null || !Files.exists(jar)) {
                System.err.println(LOCK_PREFIX + "no jar found for respawn — aborting");
                return;
            }
        }

        Path session = activeSessionFile;
        executeRespawn(jar, session);
    }

    private static void executeRespawn(Path jar, Path sessionFile) {
        if (!respawnInProgress.compareAndSet(false, true)) return;
        respawn(jar, sessionFile, true);
    }

    public static Path findCurrentJar() {
        String cp = System.getProperty("java.class.path");
        if (cp != null) {
            for (String entry : cp.split(System.getProperty("path.separator", ":"))) {
                if (entry.endsWith(".jar") && entry.contains("pijota")
                    && !entry.contains("backup"))
                    return Path.of(entry);
            }
        }
        return findJarIn(Path.of(System.getProperty("user.dir")));
    }

    public static Path findJarIn(Path projectDir) {
        if (projectDir == null) return null;
        Path target = projectDir.resolve("target");
        if (!Files.isDirectory(target)) return null;
        try (var s = Files.list(target)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("pijota-") && n.endsWith(".jar")
                    && !n.contains("original") && !n.contains("backup")
                    && !n.contains("sources") && !n.contains("javadoc");
            }).max((a, b) -> {
                try {
                    return Long.compare(Files.getLastModifiedTime(a).toMillis(),
                                       Files.getLastModifiedTime(b).toMillis());
                } catch (IOException e) { return 0; }
            }).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean backupJar(Path jar) throws IOException {
        if (jar == null || !Files.exists(jar)) return false;
        Path parent = BACKUP_JAR.getParent();
        if (parent != null) Files.createDirectories(parent);
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
        if (parent != null) Files.createDirectories(parent);
        Files.copy(BACKUP_JAR, targetJar, StandardCopyOption.REPLACE_EXISTING);
    }

    public static ProcessRunner.Result buildJar(Path projectRoot) throws IOException {
        if (projectRoot == null || !Files.exists(projectRoot.resolve("pom.xml")))
            throw new IOException("No pom.xml in " + projectRoot);
        return ProcessRunner.command("mvn", "package", "-Pfat", "-q")
            .workDir(projectRoot)
            .redirectErrorStream(true)
            .run();
    }

    public static int runInteractive(Path jar, Path sessionFile, boolean resumeSession)
            throws IOException {
        if (jar == null || !Files.exists(jar))
            throw new IOException("Jar not found: " + jar);
        List<String> cmd = buildCommand(jar, sessionFile, resumeSession);
        return ProcessRunner.command(cmd).inheritIO().runInteractive();
    }

    public static void respawn(Path jar, Path sessionFile, boolean resumeSession) {
        if (jar == null || !Files.exists(jar)) {
            System.err.println(LOCK_PREFIX + "respawn aborted: jar not found");
            return;
        }
        List<String> cmd = buildCommand(jar, sessionFile, resumeSession);
        Process child = null;
        try {
            child = new ProcessBuilder(cmd)
                .inheritIO()
                .directory(Path.of(System.getProperty("user.dir")).toFile())
                .start();

            Thread.sleep(300);
            if (!child.isAlive()) {
                int code = child.exitValue();
                System.err.println(LOCK_PREFIX + "new process exited immediately (code " + code + ")");
                if (canRollback()) {
                    System.err.println(LOCK_PREFIX + "backup available — run: java -jar target/pijota-backup.jar --continue");
                }
                return;
            }

            Process finalChild = child;
            Thread hook = new Thread(() -> {
                if (finalChild.isAlive()) finalChild.destroy();
            }, "pijota-transcend-guard");
            Runtime.getRuntime().addShutdownHook(hook);
            shutdownHook.set(hook);

        } catch (Exception e) {
            System.err.println(LOCK_PREFIX + "respawn failed: " + e.getMessage());
            if (child != null && child.isAlive()) child.destroy();
            return;
        }
        System.exit(0);
    }

    public static ProcessRunner.Result mergeBranch(String branch) throws IOException {
        if (branch == null || branch.isBlank())
            throw new IOException("No branch specified");
        return ProcessRunner.command("git", "merge", branch)
            .redirectErrorStream(true)
            .run();
    }

    public static String backupStatus() {
        if (!Files.exists(BACKUP_JAR)) return "no backup";
        try {
            long sz = Files.size(BACKUP_JAR);
            return sz <= 0 ? "empty" : (sz / 1024 + "KB");
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static List<String> buildCommand(Path jar, Path sessionFile, boolean resumeSession) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveJava());
        cmd.add("-jar");
        cmd.add(jar.toAbsolutePath().toString());

        if (sessionFile != null && Files.exists(sessionFile)) {
            cmd.add("--session-path");
            cmd.add(sessionFile.toAbsolutePath().toString());
        }

        if (resumeSession && sessionFile == null) {
            cmd.add("--continue");
        }

        cmd.addAll(preservedArgs());
        return cmd;
    }

    private static String resolveJava() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path javaBin = Path.of(javaHome, "bin", "java");
            if (Files.exists(javaBin)) return javaBin.toAbsolutePath().toString();
            javaBin = Path.of(javaHome, "bin", "java.exe");
            if (Files.exists(javaBin)) return javaBin.toAbsolutePath().toString();
        }
        String cmd = ProcessHandle.current().info().command().orElse("");
        if (!cmd.isBlank()) return cmd;
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "java.exe" : "java";
    }

    static List<String> preservedArgs() {
        List<String> out = new ArrayList<>();
        String[] orig = originalArgs;
        if (orig == null) return out;

        for (int i = 0; i < orig.length; i++) {
            String a = orig[i];

            if (isSkippedFlag(a)) {
                if (i + 1 < orig.length && isValue(orig[i + 1])) i++;
                continue;
            }

            if (a.startsWith("--")) {
                out.add(a);
                if (i + 1 < orig.length && isValue(orig[i + 1]))
                    out.add(orig[++i]);
            }
        }
        return out;
    }

    private static boolean isSkippedFlag(String a) {
        return a.equals("--session") || a.equals("--session-path") || a.equals("--resume") || a.equals("--continue")
            || a.equals("--fork") || a.equals("--export") || a.equals("--no-session")
            || a.equals("--agent") || a.equals("--print") || a.equals("--mode")
            || a.equals("--output-mode") || a.equals("--help") || a.equals("--version")
            || a.equals("--list-models") || a.equals("--api-key");
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
