package ai.pijota.agent;

import ai.pijota.util.ProcessRunner;
import ai.pijota.util.Util;

import com.fasterxml.jackson.core.type.TypeReference;

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
 * child process (rather than an in-process loop) keeps it safely concurrent with the main session — separate LLM client
 * and state — and the {@code tools} allow-list restricts what the sub-agent may do (e.g. read-only).
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

        var output = new String[] {""};
        var error = new String[] {null};
        ProcessRunner.Result result = ProcessRunner.command(cmd).workDir(Path.of(spec.cwd()))
                                        .timeout(spec.timeout() != null ? spec.timeout() : Duration.ofMinutes(3))
                                        .feedStdin(Util.MAPPER.writeValueAsString(taskJson))
                                        .consumeStdoutLines(line -> {
                                            try {
                                                Map<String, Object> ev = Util.MAPPER.readValue(line,
                                                                                new TypeReference<Map<String, Object>>() {
                                                                                });
                                                if ("done".equals(ev.get("type"))) {
                                                    output[0] = String.valueOf(ev.getOrDefault("output", ""));
                                                    if (ev.get("error") instanceof String e && !e.isBlank())
                                                        error[0] = e;
                                                }
                                            } catch (Exception ignored) {} // non-JSON / partial lines
                                        }).captureStderr(true).run();

        if (error[0] != null)
            throw new IOException(error[0]);
        if (output[0].isBlank() && result.exitCode() != 0) {
            String stderr = result.stderr() != null && !result.stderr().isBlank() ? "; " + result.stderr().strip() : "";
            throw new IOException("sub-agent failed (exit " + result.exitCode() + ")" + stderr);
        }
        return output[0];
    }

    /**
     * The command that re-invokes this pijota build in headless agent mode (native image or {@code java -cp}). Shared
     * with {@link ai.pijota.extensions.task.TaskTool} so the sub-agent launch is built exactly one way.
     */
    public static List<String> agentCommand(String cwd) {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null)
            return new ArrayList<>(List.of(currentExecutable()));
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank())
            return new ArrayList<>(List.of(currentExecutable()));
        return new ArrayList<>(List.of(Path.of(javaHome, "bin", "java").toString(), "--enable-preview", "-cp",
                                        resolveClasspath(cwd), "ai.pijota.Main"));
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
            return cwdClasses + java.io.File.pathSeparator + System.getProperty("java.class.path", "");
        return ".";
    }
}
