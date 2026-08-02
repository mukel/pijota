package org.pijota.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProcessRunnerTest {

    @Test
    void runCapturesStdout() throws IOException {
        var result = ProcessRunner.command("/bin/sh", "-c", "echo hello").run();
        assertEquals(0, result.exitCode());
        assertTrue(result.success());
        assertEquals("hello\n", result.stdout());
    }

    @Test
    void runCapturesStderr() throws IOException {
        var result = ProcessRunner.command("/bin/sh", "-c", "echo error >&2").run();
        assertEquals(0, result.exitCode());
        assertEquals("error\n", result.stderr());
    }

    @Test
    void environmentAddsVariablesToChildProcess() throws IOException {
        var result = ProcessRunner.command("/bin/sh", "-c", "printf %s \"$PIJOTA_PROVIDER/$PIJOTA_MODEL\"")
                                        .environment(Map.of("PIJOTA_PROVIDER", "openai", "PIJOTA_MODEL", "gpt-5"))
                                        .run();
        assertEquals(0, result.exitCode());
        assertEquals("openai/gpt-5", result.stdout());
    }

    @Test
    void environmentCanRemoveInheritedVariables() throws IOException {
        var env = new LinkedHashMap<String, String>();
        env.put("PIJOTA_PROVIDER", "openai");
        env.put("PIJOTA_MODEL", null);
        var result = ProcessRunner.command("/bin/sh", "-c",
                                        "printf '%s/%s' \"$PIJOTA_PROVIDER\" \"${PIJOTA_MODEL-unset}\"")
                                        .environment(env).run();
        assertEquals(0, result.exitCode());
        assertEquals("openai/unset", result.stdout());
    }

    @Test
    void runReportsNonZeroExit() throws IOException {
        var result = ProcessRunner.command("/bin/sh", "-c", "exit 5").run();
        assertEquals(5, result.exitCode());
        assertFalse(result.success());
        assertFalse(result.timedOut());
    }

    @Test
    void feedStdinPipesInput() throws IOException {
        var result = ProcessRunner.command("cat").feedStdin("hello world").run();
        assertEquals(0, result.exitCode());
        assertEquals("hello world", result.stdout());
    }

    @Test
    void consumeStdoutLinesCallsHandler() throws IOException {
        List<String> lines = new ArrayList<>();
        var result = ProcessRunner.command("/bin/sh", "-c", "printf '%s\n' a b c").consumeStdoutLines(lines::add).run();
        assertEquals(0, result.exitCode());
        assertEquals(List.of("a", "b", "c"), lines);
        assertEquals("", result.stdout());
    }

    @Test
    void maxOutputBytesTruncatesStdout() throws IOException {
        int limit = 1024;
        var result = ProcessRunner.command("/bin/sh", "-c", "seq 1 5000").maxOutputBytes(limit).run();
        assertEquals(0, result.exitCode());
        assertTrue(result.outputTruncated(), "expected truncation for large output");
        assertTrue(result.stdout().length() < 10000, "stdout should be bounded");
    }

    @Test
    void onStdoutChunkReceivesOutputWhileStillCapturingStdout() throws IOException {
        List<String> chunks = new ArrayList<>();
        var result = ProcessRunner.command("/bin/sh", "-c", "printf 'alpha'; printf 'beta'").onStdoutChunk(chunks::add)
                                        .run();

        assertEquals(0, result.exitCode());
        assertEquals("alphabeta", result.stdout());
        assertEquals("alphabeta", String.join("", chunks));
    }

    @Test
    void onStdoutChunkReceivesMergedStderrWhenRedirectingErrorStream() throws IOException {
        List<String> chunks = new ArrayList<>();
        var result = ProcessRunner.command("/bin/sh", "-c", "printf out; printf err >&2").redirectErrorStream(true)
                                        .onStdoutChunk(chunks::add).run();

        assertEquals(0, result.exitCode());
        String combined = String.join("", chunks);
        assertTrue(combined.contains("out"), combined);
        assertTrue(combined.contains("err"), combined);
        assertEquals("", result.stderr());
    }

    @Test
    void onStdoutChunkIgnoresHandlerExceptions() throws IOException {
        var seen = new AtomicReference<String>();
        var result = ProcessRunner.command("/bin/sh", "-c", "printf data").onStdoutChunk(chunk -> {
            seen.set(chunk);
            throw new RuntimeException("boom");
        }).run();

        assertEquals(0, result.exitCode());
        assertEquals("data", result.stdout());
        assertEquals("data", seen.get());
    }

    @Test
    void timeoutKillsLongRunningProcess() throws IOException {
        long start = System.currentTimeMillis();
        var result = ProcessRunner.command("/bin/sh", "-c", "sleep 30").timeout(200, TimeUnit.MILLISECONDS).run();
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(result.timedOut());
        assertTrue(elapsed < 5000, "should not wait 30s");
    }

    @Test
    void noTimeoutAllowsCompletion() throws IOException {
        var result = ProcessRunner.command("/bin/sh", "-c", "echo done").noTimeout().run();
        assertEquals(0, result.exitCode());
        assertEquals("done\n", result.stdout());
    }

    @Test
    void runInteractiveReturnsExitCode() throws IOException {
        int exit = ProcessRunner.command("/bin/sh", "-c", "exit 2").inheritIO().runInteractive();
        assertEquals(2, exit);
    }

    @Test
    void runCaptureReturnsStdoutString() throws IOException {
        String out = ProcessRunner.command("/bin/sh", "-c", "echo capture").runCapture();
        assertEquals("capture\n", out);
    }

    @Test
    void redirectErrorStreamMergesStderr() throws IOException {
        var result = ProcessRunner.command("/bin/sh", "-c", "echo out; echo err >&2").redirectErrorStream(true).run();
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("out"));
        assertTrue(result.stdout().contains("err"));
        assertEquals("", result.stderr());
    }

    @Test
    void discardStderrIgnoresErrorStream() throws IOException {
        var result = ProcessRunner.command("/bin/sh", "-c", "echo err >&2").discardStderr().run();
        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
    }

    @Test
    void captureFlagsAreRespected() throws IOException {
        var result = ProcessRunner.command("/bin/sh", "-c", "true").captureStdout(false).captureStderr(false).run();
        assertEquals(0, result.exitCode());
        assertEquals("", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void workDirChangesProcessDirectory() throws IOException {
        Path tmp = Files.createTempDirectory("pr-test");
        try {
            var result = ProcessRunner.command("pwd").workDir(tmp).run();
            assertEquals(0, result.exitCode());
            assertEquals(tmp.toRealPath().toString() + "\n", result.stdout());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void validateRejectsEmptyCommand() {
        var ex = assertThrows(IllegalArgumentException.class, ProcessRunner.command()::run);
        assertTrue(ex.getMessage().toLowerCase().contains("command"));
    }

    @Test
    void commandListRejectsNullArgument() {
        List<String> args = new ArrayList<>();
        args.add("echo");
        args.add(null);
        assertThrows(NullPointerException.class, () -> ProcessRunner.command(args));
    }

    @Test
    void validateRejectsNonPositiveTimeout() {
        var ex = assertThrows(IllegalArgumentException.class,
                                        () -> ProcessRunner.command("echo").timeout(0, TimeUnit.MILLISECONDS).run());
        assertTrue(ex.getMessage().toLowerCase().contains("timeout"));
    }

    @Test
    void onStartReceivesProcess() throws IOException {
        AtomicInteger pid = new AtomicInteger(-1);
        var result = ProcessRunner.command("/bin/sh", "-c", "echo ok").onStart(p -> pid.set((int) p.pid())).run();
        assertEquals(0, result.exitCode());
        assertTrue(pid.get() > 0);
    }

    @Test
    void killTreeWithNullIsSafe() {
        assertDoesNotThrow(() -> ProcessRunner.killTree(null));
    }

    @Test
    void abortIfAliveKillsRunningProcess() throws IOException {
        var process = new ProcessBuilder("/bin/sh", "-c", "sleep 30").start();
        ProcessRunner.abortIfAlive(process);
        assertFalse(process.isAlive());
    }

    @Test
    void abortIfAliveWithNullIsSafe() {
        assertDoesNotThrow(() -> ProcessRunner.abortIfAlive(null));
    }

    @Test
    void onStartExceptionIsIgnored() throws IOException {
        var result = ProcessRunner.command("/bin/sh", "-c", "echo ok").onStart(p -> {
            throw new RuntimeException("boom");
        }).run();
        assertEquals(0, result.exitCode());
    }

    @Test
    void consumeStdoutLinesIgnoresHandlerException() throws IOException {
        List<String> lines = new ArrayList<>();
        var result = ProcessRunner.command("/bin/sh", "-c", "printf '%s\n' a b c").consumeStdoutLines(line -> {
            if (line.equals("b"))
                throw new RuntimeException("boom");
            lines.add(line);
        }).run();
        assertEquals(0, result.exitCode());
        assertEquals(List.of("a", "c"), lines);
    }

    @Test
    void feedStdinViaSupplier() throws IOException {
        var result = ProcessRunner.command("cat").feedStdin(() -> "supplied").run();
        assertEquals("supplied", result.stdout());
    }

    @Test
    void redirectErrorStreamCapturesStderrInStdout() throws IOException {
        var result = ProcessRunner.command("/bin/sh", "-c", "echo err >&2").redirectErrorStream(true)
                                        .captureStderr(true).run();
        assertTrue(result.stdout().contains("err"));
    }

    @Test
    void missingBinaryThrowsIOExceptionClassifiedAsCommandNotFound() {
        var ex = assertThrows(IOException.class,
                                        () -> ProcessRunner.command("pijota-definitely-no-such-binary-xyz").run());
        assertTrue(ProcessRunner.isCommandNotFound(ex), ex.getMessage());
    }

    @Test
    void isCommandNotFoundMatchesTheJvmMessageAndRejectsEverythingElse() {
        assertTrue(ProcessRunner.isCommandNotFound(new IOException(
                                        "Cannot run program \"rg\": error=2, No such file or directory")));
        assertFalse(ProcessRunner.isCommandNotFound(new IOException("Stream closed")),
                                        "a real I/O error is not 'not found'");
        assertFalse(ProcessRunner.isCommandNotFound(new RuntimeException("error=2")), "only IOException counts");
        assertFalse(ProcessRunner.isCommandNotFound(null));
    }

    // -- detach: run() returns early, the process lives on, output keeps flowing --

    @Test
    void detachReturnsEarlyWithOutputSoFarAndTheProcessSurvivesItsTimeout() throws Exception {
        var signal = new CompletableFuture<Void>();
        var processRef = new AtomicReference<Process>();

        // Would time out (and be killed) after 1s; the detach at ~200ms must win and must NOT kill it.
        var runner = ProcessRunner.command("/bin/sh", "-c", "echo started; sleep 30").timeout(1, TimeUnit.SECONDS)
                                        .detachSignal(signal).onStart(processRef::set);
        var detacher = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {}
            signal.complete(null);
        });

        var result = runner.run();
        detacher.join();
        Process process = processRef.get();
        try {
            assertTrue(result.detached(), "run() must report the hand-off");
            assertFalse(result.timedOut());
            assertTrue(result.stdout().contains("started"), "output captured before the detach is returned");
            assertTrue(process.isAlive(), "a detached process must not be killed");
            Thread.sleep(1200); // past the 1s timeout: no delayed kill may fire either
            assertTrue(process.isAlive(), "a detached process must survive its would-be timeout");
        } finally {
            ProcessRunner.killTree(process);
        }
    }

    @Test
    void detachedProcessKeepsFeedingTheChunkHandler() throws Exception {
        var signal = new CompletableFuture<Void>();
        var processRef = new AtomicReference<Process>();
        var chunks = new StringBuilder();

        var result = ProcessRunner.command("/bin/sh", "-c", "echo before; sleep 1; echo after")
                                        .timeout(30, TimeUnit.SECONDS).detachSignal(signal).onStart(processRef::set)
                                        .onStdoutChunk(c -> {
                                            synchronized (chunks) {
                                                chunks.append(c);
                                            }
                                            if (c.contains("before"))
                                                signal.complete(null); // detach as soon as the first output lands
                                        }).run();

        Process process = processRef.get();
        try {
            assertTrue(result.detached());
            process.onExit().get(10, TimeUnit.SECONDS); // the daemon pump drains to EOF on its own
            Thread.sleep(100); // give the pump the moment between process exit and final read
            synchronized (chunks) {
                assertTrue(chunks.toString().contains("after"),
                                                "post-detach output must still reach the chunk handler: " + chunks);
            }
        } finally {
            ProcessRunner.killTree(process);
        }
    }

    @Test
    void unfiredDetachSignalChangesNothing() throws IOException {
        var result = ProcessRunner.command("/bin/sh", "-c", "echo plain").detachSignal(new CompletableFuture<>()).run();
        assertFalse(result.detached());
        assertEquals(0, result.exitCode());
        assertEquals("plain\n", result.stdout());
    }

    @Test
    void detachSignalWithTimeoutStillTimesOutWhenNeverFired() throws IOException {
        var result = ProcessRunner.command("/bin/sh", "-c", "sleep 30").timeout(300, TimeUnit.MILLISECONDS)
                                        .detachSignal(new CompletableFuture<>()).run();
        assertTrue(result.timedOut(), "an unfired signal must not defeat the timeout");
        assertFalse(result.detached());
    }

    @Test
    void signalFiringAfterExitIsNotADetach() throws IOException {
        var signal = new CompletableFuture<Void>();
        var result = ProcessRunner.command("/bin/sh", "-c", "echo done").detachSignal(signal).run();
        signal.complete(null); // too late, the process already finished normally
        assertFalse(result.detached());
        assertEquals("done\n", result.stdout());
    }

    /**
     * A captured child used to get a pipe for stdin that nobody wrote to and nobody closed, so it never reached EOF and
     * anything reading stdin blocked on it forever: {@code !zsh} and {@code !!bash} hung the whole frontend until they
     * were interrupted by hand. The null device gives the read an immediate EOF instead.
     *
     * <p>
     * A program that opens {@code /dev/tty} itself rather than reading stdin (nvim, htop) still waits, since it never
     * asked stdin anything -- pijota holds the terminal while it runs, and the live tail says Esc twice ends it.
     */
    @Test
    void aCapturedChildWithNothingToReadGetsEofNotAnOpenPipe() throws Exception {
        var result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                                        () -> ProcessRunner.command("/bin/sh", "-c", "read line; echo \"got:$line\"")
                                                                        .redirectErrorStream(true).run(),
                                        "reading stdin blocked, so the pipe never reached EOF");
        assertTrue(result.stdout().contains("got:"), "the read returned rather than blocking: " + result.stdout());
    }

    /** Feeding stdin still works: the null device is only for a child with nothing to read. */
    @Test
    void feedingStdinStillReachesTheChild() throws Exception {
        var result = ProcessRunner.command("/bin/sh", "-c", "read line; echo \"got:$line\"").feedStdin("hello\n")
                                        .redirectErrorStream(true).run();
        assertTrue(result.stdout().contains("got:hello"), result.stdout());
    }
}
