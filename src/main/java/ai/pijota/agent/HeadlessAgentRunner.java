package ai.pijota.agent;

import ai.pijota.extensions.ExtensionRegistry;
import ai.pijota.extensions.HeadlessToolContext;
import ai.pijota.llm.LlmClient;
import ai.pijota.messages.Message;
import ai.pijota.tools.ToolRegistry;
import ai.pijota.util.ErrorUtil;
import ai.pijota.util.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class HeadlessAgentRunner {
    private static final Logger logger = LoggerFactory.getLogger(HeadlessAgentRunner.class);

    private HeadlessAgentRunner() {}

    public static int run(LlmClient llm, ExtensionRegistry extensions) {
        var writerRef = new BufferedWriter[] {null};
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> input = Util.MAPPER.readValue(System.in, Map.class);

            String description = (String) input.getOrDefault("description", input.getOrDefault("task", ""));
            String systemPrompt = (String) input.get("systemPrompt");
            @SuppressWarnings("unchecked")
            List<String> toolNames = (List<String>) input.getOrDefault("tools", List.of());
            String cwd = (String) input.getOrDefault("cwd", System.getProperty("user.dir"));
            int depth = input.get("subagentDepth") instanceof Number n ? n.intValue() : 0;
            String taskProvider = (String) input.get("provider");
            String taskModel = (String) input.get("model");
            String taskApiKey = (String) input.get("apiKey");
            boolean parentLogs = Boolean.TRUE.equals(input.get("parentLogs"));
            if (taskProvider != null && taskModel != null) {
                String key = taskApiKey != null ? taskApiKey : llm.apiKey();
                llm.setProviderAndModel(taskProvider, taskModel, key);
            }

            var registry = buildRegistry(toolNames, depth, llm, systemPrompt, cwd, extensions);
            var agentLoop = AgentLoop.builder(llm, registry, cwd).build();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                agentLoop.addSystemPrompt(systemPrompt);
            }

            Path logFile = null;
            String logPath = (String) input.get("logFile");
            if (logPath != null && !logPath.isBlank()) {
                logFile = Path.of(logPath);
                Path parent = logFile.getParent();
                if (parent != null)
                    Files.createDirectories(parent);
                if (!parentLogs) {
                    writerRef[0] = Files.newBufferedWriter(logFile, StandardOpenOption.CREATE,
                                                    StandardOpenOption.APPEND);
                }
                emit(new LogEvent(logFile.toString()));
            }

            var toolCount = new AtomicInteger(0);

            agentLoop.addEventListener(event -> {
                switch (event) {
                    case AgentEvent.ToolStart ts -> {
                        toolCount.incrementAndGet();
                        emit(new ToolStartEvent(ts.toolName(), ts.argumentsSupplier().get()));
                        appendLog(writerRef[0], "-> " + ts.toolName() + " " + ts.argumentsSupplier().get() + "\n");
                    }
                    case AgentEvent.ToolResult(Message.ToolResult msg) -> {
                        emit(new ToolResultEvent(msg.toolName(), msg.isError(), msg.details()));
                        emit(new ToolEndEvent(msg.toolName()));
                    }
                    case AgentEvent.MessageStreamDelta d -> {
                        String delta = d.delta();
                        emit(new StreamEvent(delta));
                        appendLog(writerRef[0], delta);
                        int added = Util.countNewlines(delta);
                        if (added > 0)
                            emit(new StreamLinesEvent(added));
                    }
                    case AgentEvent.MessageStreamEnd _ -> {
                        // emitted after each LLM response completes
                    }
                    default -> {}
                }
            });

            List<Message> messages = agentLoop.prompt(description).get(5, TimeUnit.MINUTES);

            int turns = 0;
            String finalOutput = "";
            for (var msg : messages) {
                if (msg instanceof Message.Assistant a) {
                    if (a.content() != null && !a.content().isBlank())
                        finalOutput = a.content();
                    turns++;
                }
            }

            appendLog(writerRef[0], "\n--- done (" + turns + " turns) ---\n");

            if (logFile != null) {
                try {
                    String prefix = logFile.getFileName().toString().replace(".log", "");
                    Path dir = logFile.getParent();
                    Files.writeString(dir.resolve(prefix + "_input.md"), description);
                    Files.writeString(dir.resolve(prefix + "_output.md"), finalOutput);
                } catch (IOException ignored) {}
            }

            long tokens = llm.totalInputTokens() + llm.totalOutputTokens();
            emit(new DoneEvent(finalOutput, 0, turns, toolCount.get(), tokens, null));

        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank())
                msg = e.getClass().getSimpleName();
            String fullError = msg + "\n" + ErrorUtil.stackTrace(e);
            logger.error("Headless agent run failed: {}", fullError);
            System.err.println("ERROR: " + msg);
            appendLog(writerRef[0], "\n--- ERROR ---\n" + fullError + "\n");
            try {
                emit(new DoneEvent("", 1, 0, 0, 0, fullError));
            } catch (Exception emitEx) {
                logger.error("Failed to emit headless agent error event", emitEx);
                appendLog(writerRef[0], "\n--- EMIT FAILED ---\n" + emitEx + "\n");
            }
            return 1;
        } finally {
            if (writerRef[0] != null) {
                try {
                    writerRef[0].close();
                } catch (IOException ignored) {}
            }
        }
        return 0;
    }

    /**
     * Appends to the run log if a writer is attached, flushing so a live tail sees it; a broken writer is ignored (the
     * log is best-effort).
     */
    private static void appendLog(BufferedWriter writer, String text) {
        if (writer == null)
            return;
        try {
            writer.write(text);
            writer.flush();
        } catch (IOException ignored) {}
    }

    private static ToolRegistry buildRegistry(List<String> names, int depth, LlmClient llm, String systemPrompt,
                                    String cwd, ExtensionRegistry extensions) {
        var defaults = ToolRegistry.createDefault();
        var registry = new ToolRegistry();
        for (String name : names) {
            defaults.get(name).ifPresent(registry::register);
        }
        if (extensions != null) {
            extensions.contributeHeadlessTools(new HeadlessToolContext(names, depth, llm, registry, systemPrompt,
                                            Path.of(cwd)));
        }
        return registry;
    }

    private static void emit(Object event) {
        try {
            System.out.println(Util.MAPPER.writeValueAsString(event));
        } catch (Exception ignored) {}
    }

    // --- JSON event records ---

    record LogEvent(String type, String path) {
        LogEvent(String path) {
            this("log", path);
        }
    }

    record ToolStartEvent(String type, String tool, String args) {
        ToolStartEvent(String tool, String args) {
            this("tool_start", tool, args);
        }
    }

    record ToolResultEvent(String type, String tool, boolean error, Map<String, Object> details) {
        ToolResultEvent(String tool, boolean error, Map<String, Object> details) {
            this("tool_result", tool, error, details == null ? Map.of() : details);
        }
    }

    record ToolEndEvent(String type, String tool) {
        ToolEndEvent(String tool) {
            this("tool_end", tool);
        }
    }

    record StreamEvent(String type, String delta) {
        StreamEvent(String delta) {
            this("stream", delta);
        }
    }

    record StreamLinesEvent(String type, int count) {
        StreamLinesEvent(int count) {
            this("stream_lines", count);
        }
    }

    record DoneEvent(String type, String output, int exitCode, int turns, int toolCount, long tokens, String error) {
        DoneEvent(String output, int exitCode, int turns, int toolCount, long tokens, String error) {
            this("done", output, exitCode, turns, toolCount, tokens, error);
        }
    }
}
