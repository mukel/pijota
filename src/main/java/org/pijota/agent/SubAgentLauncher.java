package org.pijota.agent;

import org.pijota.util.Platform;
import org.pijota.util.ProcessRunner;
import org.pijota.util.Util;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a one-shot sub-agent in a separate {@code pijota --agent --no-session} process and returns its final answer. A
 * child process (rather than an in-process loop) keeps it safely concurrent with the main session (separate LLM client
 * and state), and the {@code tools} allow-list restricts what the sub-agent may do (e.g. read-only).
 */
public final class SubAgentLauncher {

    private SubAgentLauncher() {}

    /**
     * @param task
     *            the user task / question for the sub-agent
     * @param systemPrompt
     *            extra system instructions (may be empty)
     * @param tools
     *            allow-list of tool names the sub-agent may use (empty = all)
     */
    public record Spec(String task, String systemPrompt, List<String> tools, String cwd, String provider, String model,
                                    String apiKey, Duration timeout) {
    }

    /** Spawns the sub-agent, feeds the task on stdin, and returns the {@code done} event's output. */
    public static String run(Spec spec) throws Exception {
        List<String> cmd = agentCommand(spec.cwd());
        cmd.add("--agent");
        cmd.add("--no-session");
        if (spec.model() != null && !spec.model().isBlank()) {
            cmd.add("--model");
            cmd.add(spec.model());
        }

        var lines = new ArrayList<String>();
        ProcessRunner.Result result = ProcessRunner.command(cmd).workDir(Path.of(spec.cwd()))
                                        .timeout(spec.timeout() != null ? spec.timeout() : Duration.ofMinutes(3))
                                        .feedStdin(buildTaskJson(spec)).consumeStdoutLines(lines::add)
                                        .captureStderr(true).run();
        return resolveOutput(lines, result.exitCode(), result.stderr());
    }

    /** Build the JSON task spec fed to the sub-agent on stdin (optional fields are omitted when null/blank/empty). */
    static String buildTaskJson(Spec spec) throws IOException {
        var taskJson = new LinkedHashMap<String, Object>();
        taskJson.put("description", spec.task());
        if (spec.systemPrompt() != null && !spec.systemPrompt().isBlank())
            taskJson.put("systemPrompt", spec.systemPrompt());
        if (spec.tools() != null && !spec.tools().isEmpty())
            taskJson.put("tools", spec.tools());
        taskJson.put("cwd", spec.cwd());
        taskJson.put("subagentDepth", 1);
        if (spec.provider() != null)
            taskJson.put("provider", spec.provider());
        if (spec.model() != null)
            taskJson.put("model", spec.model());
        if (spec.apiKey() != null && !spec.apiKey().isBlank())
            taskJson.put("apiKey", spec.apiKey());
        return Util.MAPPER.writeValueAsString(taskJson);
    }

    /**
     * Reduce the sub-agent's stdout event lines (plus exit code / stderr) to its output, or throw: the {@code done}
     * event's {@code error} field wins; otherwise a blank output with a non-zero exit is reported as a failure.
     */
    static String resolveOutput(List<String> stdoutLines, int exitCode, String stderr) throws IOException {
        String output = "";
        String error = null;
        for (String line : stdoutLines) {
            try {
                Map<String, Object> ev = Util.MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {
                });
                if ("done".equals(ev.get("type"))) {
                    output = String.valueOf(ev.getOrDefault("output", ""));
                    if (ev.get("error") instanceof String e && !e.isBlank())
                        error = e;
                }
            } catch (Exception ignored) {} // non-JSON / partial lines
        }
        if (error != null)
            throw new IOException(error);
        if (output.isBlank() && exitCode != 0) {
            String tail = stderr != null && !stderr.isBlank() ? "; " + stderr.strip() : "";
            throw new IOException("sub-agent failed (exit " + exitCode + ")" + tail);
        }
        return output;
    }

    /**
     * The command that re-invokes this pijota build in headless agent mode (native image or {@code java -cp}). Shared
     * with {@link org.pijota.extensions.task.TaskTool} so the sub-agent launch is built exactly one way.
     */
    public static List<String> agentCommand(String cwd) {
        if (Platform.isNativeImage())
            return new ArrayList<>(List.of(currentExecutable()));
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank())
            return new ArrayList<>(List.of(currentExecutable()));
        return new ArrayList<>(List.of(Platform.javaBinary().toString(), "--enable-preview", "-cp",
                                        resolveClasspath(cwd), "org.pijota.Main"));
    }

    private static String currentExecutable() {
        return ProcessHandle.current().info().command().orElseThrow(
                                        () -> new IllegalStateException("cannot resolve current executable"));
    }

    /** The classpath to launch the child with: the parent's classpath, with fat-jar / target-classes fallbacks. */
    private static String resolveClasspath(String cwd) {
        String cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isBlank())
            return cp;
        try {
            var ourJar = Path.of(SubAgentLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.exists(ourJar) && ourJar.toString().endsWith(".jar"))
                return ourJar.toString();
            var classesDir = ourJar.resolve("..").normalize();
            if (Files.isDirectory(classesDir) && Files.exists(classesDir.resolve("ai/pijota/Main.class")))
                return classesDir.toString();
        } catch (Exception ignored) {}
        Path cwdClasses = Path.of(cwd).resolve("target/classes");
        if (Files.isDirectory(cwdClasses) && Files.exists(cwdClasses.resolve("ai/pijota/Main.class")))
            return cwdClasses + File.pathSeparator + System.getProperty("java.class.path", "");
        return ".";
    }
}
