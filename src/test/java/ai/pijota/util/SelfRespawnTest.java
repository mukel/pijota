package ai.pijota.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SelfRespawnTest {

    private final Path recoveryFile = Path.of("target", ".transcend-session");
    private final Path restartFile = Path.of("target", ".transcend-restart");
    private final Path backupJar = Path.of("target", "pijota-backup.jar");
    private String originalClasspath;

    @BeforeEach
    void setUp() throws IOException, ReflectiveOperationException {
        originalClasspath = System.getProperty("java.class.path");
        Files.deleteIfExists(recoveryFile);
        Files.deleteIfExists(restartFile);
        Files.deleteIfExists(backupJar);
        resetRespawnState();
    }

    @SuppressWarnings("unchecked")
    private void resetRespawnState() throws ReflectiveOperationException {
        AtomicBoolean pending = (AtomicBoolean) getStaticField("pending");
        pending.set(false);
        AtomicBoolean respawnInProgress = (AtomicBoolean) getStaticField("respawnInProgress");
        respawnInProgress.set(false);
        ((AtomicReference<Path>) getStaticField("pendingJar")).set(null);
        ((AtomicReference<String>) getStaticField("pendingMergeBranch")).set(null);
        ((AtomicReference<Path>) getStaticField("pendingMergeWorktree")).set(null);
    }

    private Object getStaticField(String name) throws ReflectiveOperationException {
        Field field = SelfRespawn.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(recoveryFile);
        Files.deleteIfExists(restartFile);
        if (originalClasspath != null) {
            System.setProperty("java.class.path", originalClasspath);
        }
    }

    @Test
    void backupJarCreatesBackup() throws IOException {
        Path jar = createTempJar("test");
        assertTrue(SelfRespawn.backupJar(jar));
        assertTrue(SelfRespawn.canRollback());
        assertTrue(SelfRespawn.backupStatus().endsWith("KB") || SelfRespawn.backupStatus().matches("\\d+"));
    }

    @Test
    void rollbackRestoresBackup() throws IOException {
        Path jar = createTempJar("v1");
        assertTrue(SelfRespawn.backupJar(jar));
        Files.writeString(jar, "v2");
        assertEquals("v2", Files.readString(jar));
        SelfRespawn.rollback(jar);
        assertTrue(Files.readString(jar).contains("v1"));
    }

    @Test
    void rollbackRejectsMissingBackup() {
        Path target = Path.of("target", "pijota-test.jar");
        var ex = assertThrows(IOException.class, () -> SelfRespawn.rollback(target));
        assertTrue(ex.getMessage().contains("No backup"));
    }

    @Test
    void backupJarRejectsMissingFile() throws IOException {
        assertFalse(SelfRespawn.backupJar(Path.of("/does/not/exist.jar")));
    }

    @Test
    void backupStatusReportsEmptyBackup() throws IOException {
        Files.createDirectories(backupJar.getParent());
        Files.writeString(backupJar, "");
        assertEquals("empty", SelfRespawn.backupStatus());
    }

    @Test
    void findJarInReturnsLatestJar() throws IOException {
        Path dir = Files.createTempDirectory("sr-test");
        Path targetDir = dir.resolve("target");
        Files.createDirectories(targetDir);
        Path jar1 = targetDir.resolve("pijota-1.jar");
        Path jar2 = targetDir.resolve("pijota-2.jar");
        Files.writeString(jar1, "v1");
        Files.writeString(jar2, "v2");
        Files.setLastModifiedTime(jar1, java.nio.file.attribute.FileTime.fromMillis(1000));
        Files.setLastModifiedTime(jar2, java.nio.file.attribute.FileTime.fromMillis(2000));

        assertEquals(jar2, SelfRespawn.findJarIn(dir));

        Files.walk(dir).sorted((a, b) -> -a.compareTo(b)).forEach(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {}
        });
    }

    @Test
    void findJarInReturnsNullForMissingTarget() throws IOException {
        Path dir = Files.createTempDirectory("sr-test");
        assertNull(SelfRespawn.findJarIn(dir));
        Files.deleteIfExists(dir);
    }

    @Test
    void findJarInReturnsNullForNullProjectDir() {
        assertNull(SelfRespawn.findJarIn(null));
    }

    @Test
    void findCurrentJarFromClasspath() throws IOException {
        Path dir = Files.createTempDirectory("sr-cp");
        Path targetDir = dir.resolve("target");
        Files.createDirectories(targetDir);
        Path jar = targetDir.resolve("pijota-current.jar");
        Files.writeString(jar, "payload");
        System.setProperty("java.class.path", jar.toString());
        assertEquals(jar, SelfRespawn.findCurrentJar());
        Files.walk(dir).sorted((a, b) -> -a.compareTo(b)).forEach(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {}
        });
    }

    @Test
    void readRecoverySessionRoundTrip() throws IOException {
        Path session = Files.createTempFile("session", ".json");
        Files.createDirectories(recoveryFile.getParent());
        Files.writeString(recoveryFile, session.toString());
        assertTrue(SelfRespawn.canRecover());
        assertEquals(session, SelfRespawn.readRecoverySession());
        SelfRespawn.clearRecoveryFile();
        assertFalse(Files.exists(recoveryFile));
        assertNull(SelfRespawn.readRecoverySession());
    }

    @Test
    void readRecoverySessionIgnoresMissingFile() {
        assertNull(SelfRespawn.readRecoverySession());
    }

    @Test
    void readRecoverySessionReturnsNullForMissingSessionPath() throws IOException {
        Files.createDirectories(recoveryFile.getParent());
        Files.writeString(recoveryFile, "/does/not/exist.json");
        assertNull(SelfRespawn.readRecoverySession());
    }

    @Test
    void canRecoverRejectsEmptyFile() throws IOException {
        Files.createDirectories(recoveryFile.getParent());
        Files.writeString(recoveryFile, "");
        assertFalse(SelfRespawn.canRecover());
    }

    @Test
    void readRestartRequestRoundTrip() throws IOException {
        Properties props = new Properties();
        List<String> command = List.of("java", "-jar", "target/pijota.jar", "--worker");
        props.setProperty("argc", Integer.toString(command.size()));
        for (int i = 0; i < command.size(); i++) {
            props.setProperty("arg." + i, command.get(i));
        }
        Files.createDirectories(restartFile.getParent());
        try (var out = Files.newOutputStream(restartFile)) {
            props.store(out, "test");
        }

        Optional<List<String>> read = SelfRespawn.readRestartRequest();
        assertTrue(read.isPresent());
        assertEquals(command, read.get());

        SelfRespawn.clearRestartRequest();
        assertFalse(Files.exists(restartFile));
        assertTrue(SelfRespawn.readRestartRequest().isEmpty());
    }

    @Test
    void readRestartRequestReturnsEmptyForInvalidFile() throws IOException {
        Files.createDirectories(restartFile.getParent());
        Files.writeString(restartFile, "not a properties file");
        assertTrue(SelfRespawn.readRestartRequest().isEmpty());
    }

    @Test
    void readRestartRequestReturnsEmptyForZeroArgs() throws IOException {
        Properties props = new Properties();
        props.setProperty("argc", "0");
        Files.createDirectories(restartFile.getParent());
        try (var out = Files.newOutputStream(restartFile)) {
            props.store(out, "test");
        }
        assertTrue(SelfRespawn.readRestartRequest().isEmpty());
    }

    @Test
    void requestRespawnRejectsNullJar() {
        assertFalse(SelfRespawn.requestRespawn(null));
    }

    @Test
    void requestRespawnRejectsMissingJar() {
        assertFalse(SelfRespawn.requestRespawn(Path.of("/does/not/exist.jar")));
    }

    @Test
    void requestRespawnAcceptsValidJar() throws IOException {
        Path jar = createTempJar("payload");
        Path session = Files.createTempFile("session", ".json");
        SelfRespawn.setSessionFile(session);
        assertTrue(SelfRespawn.requestRespawn(jar));
        assertTrue(SelfRespawn.isPending());
        assertTrue(Files.exists(recoveryFile));
        assertEquals(session.toString(), Files.readString(recoveryFile).strip());
    }

    @Test
    void requestRespawnSkipsDuplicate() throws IOException {
        Path jar = createTempJar("payload");
        SelfRespawn.setSessionFile(Files.createTempFile("session", ".json"));
        assertTrue(SelfRespawn.requestRespawn(jar));
        assertFalse(SelfRespawn.requestRespawn(jar));
    }

    @Test
    void respawnWithMissingJarRecordsError() {
        SelfRespawn.respawn(Path.of("/does/not/exist.jar"), null, false);
        assertNotNull(SelfRespawn.lastRespawnError());
        assertTrue(SelfRespawn.lastRespawnError().contains("jar not found"));
    }

    @Test
    void maybeRespawnDoesNothingWhenNotPending() {
        SelfRespawn.maybeRespawn();
        assertNull(SelfRespawn.lastRespawnError());
    }

    @Test
    void isPendingAndRespawnInProgressDefaults() {
        assertFalse(SelfRespawn.isPending());
        assertFalse(SelfRespawn.isRespawnInProgress());
    }

    @Test
    void preservedArgsFiltersSkippedFlags() {
        SelfRespawn.storeArgs(new String[] {"--model", "gpt-4", "--session", "s.json", "--continue", "--verbose"});
        List<String> preserved = SelfRespawn.preservedArgs();
        assertTrue(preserved.contains("--model"));
        assertTrue(preserved.contains("gpt-4"));
        assertTrue(preserved.contains("--verbose"));
        assertFalse(preserved.contains("--session"));
        assertFalse(preserved.contains("s.json"));
        assertFalse(preserved.contains("--continue"));
    }

    @Test
    void preservedArgsHandlesFlagsWithoutValues() {
        SelfRespawn.storeArgs(new String[] {"--verbose"});
        assertEquals(List.of("--verbose"), SelfRespawn.preservedArgs());
    }

    @Test
    void preservedArgsSkipsValueOfSkippedFlag() {
        SelfRespawn.storeArgs(new String[] {"--model", "gpt-4", "--session", "s.json", "--continue"});
        List<String> preserved = SelfRespawn.preservedArgs();
        assertTrue(preserved.contains("--model"));
        assertTrue(preserved.contains("gpt-4"));
        assertFalse(preserved.contains("s.json"));
        assertFalse(preserved.contains("--session"));
    }

    @Test
    void resolveJavaReturnsNonBlank() {
        String java = SelfRespawn.resolveJava();
        assertNotNull(java);
        assertFalse(java.isBlank());
    }

    @Test
    void buildCommandViaReflection() throws Exception {
        Path jar = createTempJar("x");
        Path session = Files.createTempFile("session", ".json");
        Method method = SelfRespawn.class.getDeclaredMethod("buildCommand", Path.class, Path.class, boolean.class,
                                        String.class, Path.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> cmd = (List<String>) method.invoke(null, jar, session, true, "feature/x", session.getParent());
        assertTrue(cmd.contains(SelfRespawn.resolveJava()));
        assertTrue(cmd.contains("-jar"));
        assertTrue(cmd.contains(jar.toAbsolutePath().toString()));
        assertTrue(cmd.contains("--worker"));
        assertTrue(cmd.contains("--session-path"));
        assertTrue(cmd.contains(session.toAbsolutePath().toString()));
        assertTrue(cmd.contains("--transcend-merge-branch"));
        assertTrue(cmd.contains("feature/x"));
    }

    @Test
    void buildCommandResumeWithoutSessionAddsContinue() throws Exception {
        Path jar = createTempJar("x");
        Method method = SelfRespawn.class.getDeclaredMethod("buildCommand", Path.class, Path.class, boolean.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> cmd = (List<String>) method.invoke(null, jar, null, true);
        assertTrue(cmd.contains("--continue"));
    }

    private Path createTempJar(String content) throws IOException {
        Path jar = Files.createTempFile("pijota-test", ".jar");
        Files.writeString(jar, content);
        return jar;
    }
}
