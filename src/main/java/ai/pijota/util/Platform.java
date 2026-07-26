package ai.pijota.util;

import java.nio.file.Path;
import java.util.Locale;

/** Runtime environment checks. */
public final class Platform {
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private Platform() {}

    /**
     * Whether this process is a GraalVM native image (vs. running on the JVM). The marker property is set only inside a
     * native image. Used to gate JVM-only features such as transcendence (which rebuilds via Maven and respawns a jar).
     */
    public static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    public static boolean isWindows() {
        return OS.contains("win");
    }

    public static boolean isMac() {
        return OS.contains("mac") || OS.contains("darwin");
    }

    /**
     * The shell to use when none is configured and {@code $SHELL} is unset: a POSIX shell on Unix; on Windows a
     * PATH-resolved {@code bash} (Git Bash / WSL), since pijota's shell tooling is bash-centric and {@code cmd.exe} is
     * not a meaningful substitute. Always overridable via the {@code shell_path} setting.
     */
    public static String defaultShell() {
        return isWindows() ? "bash" : "/bin/bash";
    }

    public static Path javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
    }

    /** Shell flag for running a command string. */
    public static String shellCommandFlag(String shellPath) {
        String shell = shellPath != null ? shellPath.toLowerCase(Locale.ROOT) : "";
        return isWindows() && (shell.endsWith("bash") || shell.endsWith("bash.exe")) ? "-lc" : "-c";
    }
}
