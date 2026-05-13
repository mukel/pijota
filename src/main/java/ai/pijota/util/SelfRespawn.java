package ai.pijota.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class SelfRespawn {

    static final Path BACKUP_JAR = Path.of("target", "pijota-backup.jar");

    private static volatile Path activeSessionFile;
    private static volatile String[] originalArgs;
    private static volatile boolean pending;
    private static volatile boolean respawnInProgress;
    private static volatile Path pendingJar;

    private SelfRespawn() {}

    public static void setSessionFile(Path file) { activeSessionFile = file; }

    public static void storeArgs(String[] args) { originalArgs = args.clone(); }

    public static void requestRespawn(Path jar) { pending = true; pendingJar = jar; }
    public static void requestRespawn() { pending = true; pendingJar = null; }

    public static boolean isPending() { return pending && !respawnInProgress; }

    public static void maybeRespawn() {
        if (!isPending()) return;
        pending = false;
        Path jar = pendingJar;
        pendingJar = null;
        if (jar == null) jar = findJarIn(Path.of(System.getProperty("user.dir")));
        if (jar != null && Files.exists(jar)) {
            Path session = activeSessionFile;
            executeRespawn(jar, session);
        }
    }

    private static void executeRespawn(Path jar, Path sessionFile) {
        if (respawnInProgress) return;
        respawnInProgress = true;
        respawn(jar, sessionFile, true);
    }

    public static Path findCurrentJar() {
        String cp = System.getProperty("java.class.path");
        if (cp != null) {
            for (String entry : cp.split(System.getProperty("path.separator", ":"))) {
                if (entry.endsWith(".jar") && entry.contains("pijota"))
                    return Path.of(entry);
            }
        }
        return findJarIn(Path.of(System.getProperty("user.dir")));
    }

    public static Path findJarIn(Path projectDir) {
        Path target = projectDir.resolve("target");
        if (!Files.isDirectory(target)) return null;
        try (var s = Files.list(target)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("pijota-") && n.endsWith(".jar")
                    && !n.contains("original") && !n.contains("backup")
                    && !n.contains("sources") && !n.contains("javadoc");
            }).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public static void backupJar(Path jar) throws IOException {
        if (jar != null && Files.exists(jar))
            Files.copy(jar, BACKUP_JAR, StandardCopyOption.REPLACE_EXISTING);
    }

    public static boolean canRollback() { return Files.exists(BACKUP_JAR); }

    public static void rollback(Path targetJar) throws IOException {
        if (!Files.exists(BACKUP_JAR))
            throw new IOException("No backup jar at " + BACKUP_JAR);
        if (targetJar != null)
            Files.copy(BACKUP_JAR, targetJar, StandardCopyOption.REPLACE_EXISTING);
    }

    public static ProcessRunner.Result buildJar(Path projectRoot) throws IOException {
        if (!Files.exists(projectRoot.resolve("pom.xml")))
            throw new IOException("No pom.xml in " + projectRoot);
        return ProcessRunner.command("mvn", "package", "-Pfat", "-q")
            .workDir(projectRoot)
            .redirectErrorStream(true)
            .run();
    }

    public static int runInteractive(Path jar, Path sessionFile, boolean resumeSession)
            throws IOException {
        List<String> cmd = buildCommand(jar, sessionFile, resumeSession);
        return ProcessRunner.command(cmd).inheritIO().runInteractive();
    }

    public static void respawn(Path jar, Path sessionFile, boolean resumeSession) {
        List<String> cmd = buildCommand(jar, sessionFile, resumeSession);
        try {
            Process p = new ProcessBuilder(cmd)
                .inheritIO()
                .directory(Path.of(System.getProperty("user.dir")).toFile())
                .start();
            Thread.sleep(200);
            if (!p.isAlive()) {
                System.err.println("[pijota] new process exited immediately (code " + p.exitValue() + ")");
                return;
            }
            Runtime.getRuntime().addShutdownHook(
                new Thread(() -> { if (p.isAlive()) p.destroy(); }, "pijota-respawn-guard"));
        } catch (Exception e) {
            System.err.println("[pijota] respawn failed: " + e.getMessage());
            return;
        }
        System.exit(0);
    }

    public static ProcessRunner.Result mergeBranch(String branch) throws IOException {
        return ProcessRunner.command("git", "merge", branch)
            .redirectErrorStream(true)
            .run();
    }

    public static String backupStatus() {
        if (!Files.exists(BACKUP_JAR)) return "no backup";
        try {
            return Files.size(BACKUP_JAR) / 1024 + "KB";
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static List<String> buildCommand(Path jar, Path sessionFile, boolean resumeSession) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
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
        return a.equals("--session-path") || a.equals("--resume") || a.equals("--continue")
            || a.equals("--agent") || a.equals("--print") || a.equals("--mode")
            || a.equals("--output-mode") || a.equals("--help") || a.equals("--version")
            || a.equals("--list-models") || a.equals("--api-key");
    }

    private static boolean isValue(String a) {
        return !a.startsWith("--");
    }
}
