package ai.pijota.util;

/** Runtime environment checks. */
public final class Platform {
    private Platform() {}

    /**
     * Whether this process is a GraalVM native image (vs. running on the JVM). The marker property is set only inside a
     * native image. Used to gate JVM-only features such as transcendence (which rebuilds via Maven and respawns a jar).
     */
    public static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }
}
