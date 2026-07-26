package org.pijota;

public final class PijotaVersion {
    // Source-tree fallback used by tests and `mvn exec:java`, where no jar manifest exists yet.
    private static final String DEV_VERSION = "0.1.0-SNAPSHOT";

    private PijotaVersion() {}

    public static String current() {
        String version = PijotaVersion.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? DEV_VERSION : version;
    }
}
