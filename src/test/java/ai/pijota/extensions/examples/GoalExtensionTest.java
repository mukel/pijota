package ai.pijota.extensions.examples;

import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.ExtensionContext;
import ai.pijota.extensions.ExtensionEvent;
import ai.pijota.extensions.ExtensionResult;
import ai.pijota.extensions.ExtensionRuntime;
import ai.pijota.extensions.skills.SkillLoader;
import ai.pijota.llm.LlmClient;
import ai.pijota.llm.StreamResult;
import ai.pijota.messages.Message;
import ai.pijota.modes.SlashCommand;
import ai.pijota.tools.Tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import ai.pijota.extensions.ExtensionKeybinding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GoalExtensionTest {

    @Test
    void nameAndDescriptionAreSet() {
        var ext = new GoalExtension();
        assertEquals("goal", ext.name());
        assertFalse(ext.description().isBlank());
    }

    @Test
    void createGoalToolStartsActiveGoal() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);

        var result = execute(fake.tool("create_goal"), Map.of("objective", "write tests"));

        assertFalse(result.isError());
        assertTrue(fake.lastCustomMessage().contains("write tests"));
        assertTrue(fake.status().contains("write tests"));
    }

    @Test
    void createGoalToolRendersSummary() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        var tool = fake.tool("create_goal").toTool();

        var result = tool.execute("id", Map.of("objective", "write tests"), ".").join();
        var view = tool.renderResult(Map.of("objective", "write tests"), result, ".");

        assertTrue(view.summary().contains("Created goal: write tests"));
    }

    @Test
    void createGoalRendererHandlesErrors() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        var tool = fake.tool("create_goal").toTool();

        var result = tool.execute("id", Map.of("objective", ""), ".").join();
        var view = tool.renderResult(Map.of("objective", ""), result, ".");

        assertTrue(view.summary().contains("Failed"));
    }

    @Test
    void createGoalFailsWithoutReplaceWhenGoalExists() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "first"));

        var result = execute(fake.tool("create_goal"), Map.of("objective", "second"));

        assertTrue(result.isError());
        assertTrue(result.content().contains("already exists"));
    }

    @Test
    void createGoalWithReplaceOverridesExisting() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "first"));

        var result = execute(fake.tool("create_goal"), Map.of("objective", "second", "replace_existing", true));

        assertFalse(result.isError());
        assertTrue(result.content().contains("second"));
    }

    @Test
    void getGoalReturnsCurrentState() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x", "token_budget", 500));

        var result = execute(fake.tool("get_goal"), Map.of());

        assertFalse(result.isError());
        assertTrue(result.content().contains("objective: x"));
        assertTrue(result.content().contains("usage: 0/500"));
    }

    @Test
    void updateGoalCanComplete() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x"));

        var result = execute(fake.tool("update_goal"), Map.of("status", "complete"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("status: complete"));
    }

    @Test
    void pauseAndResumePreserveElapsedTime() throws InterruptedException {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x"));
        Thread.sleep(5);
        execute(fake.tool("update_goal"), Map.of("status", "pause"));
        Thread.sleep(20);
        execute(fake.tool("update_goal"), Map.of("status", "resume"));

        var get = execute(fake.tool("get_goal"), Map.of());
        assertFalse(get.isError());
        assertTrue(get.content().contains("status: active"));
    }

    @Test
    void addProgressAppendsNote() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x"));

        var result = execute(fake.tool("add_goal_progress"), Map.of("note", "done step 1"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("done step 1"));
    }

    @Test
    void budgetLimitIsEnforcedAtTurnEnd() {
        var fake = new FakeExtensionAPI();
        fake.llm().setTokens(0, 0);
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x", "token_budget", 100));

        fake.llm().setTokens(60, 50); // 110 total, exceeds 100
        fake.fireTurnEnd(1);

        var get = execute(fake.tool("get_goal"), Map.of());
        assertTrue(get.content().contains("status: budget-limited"));
    }

    @Test
    void budgetLimitIsEnforcedAtTurnStart() {
        var fake = new FakeExtensionAPI();
        fake.llm().setTokens(0, 0);
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x", "token_budget", 100));

        fake.llm().setTokens(60, 50); // 110 total, exceeds 100
        fake.fireTurnStart(1);

        var get = execute(fake.tool("get_goal"), Map.of());
        assertTrue(get.content().contains("status: budget-limited"));
    }

    @Test
    void continueOnIdleIsDebounced() throws InterruptedException {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        execute(fake.tool("create_goal"), Map.of("objective", "x", "continue_on_idle", true));

        fake.fireTurnEnd(1);
        fake.fireTurnEnd(2);
        fake.fireTurnEnd(3);

        assertEquals(1, fake.userMessages().size());

        Thread.sleep(1100);
        fake.fireTurnEnd(4);

        assertEquals(2, fake.userMessages().size());
    }

    @Test
    void slashCommandCreatesGoal() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);

        fake.slashCommand("goal").handler().accept("implement feature X");

        assertTrue(fake.status().contains("implement feature X"));
    }

    @Test
    void slashCommandPauseAndResumeWork() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        fake.slashCommand("goal").handler().accept("x");

        fake.slashCommand("goal").handler().accept("pause");
        assertTrue(fake.status().contains("paused"));

        fake.slashCommand("goal").handler().accept("resume");
        assertTrue(fake.status().contains("resumed"));
    }

    @Test
    void slashCommandProgressAddsEntry() {
        var fake = new FakeExtensionAPI();
        new GoalExtension().init(fake);
        fake.slashCommand("goal").handler().accept("x");

        fake.slashCommand("goal").handler().accept("progress finished setup");

        var get = execute(fake.tool("get_goal"), Map.of());
        assertTrue(get.content().contains("finished setup"));
    }

    @Test
    void sessionReadyRestoresLatestGoal() {
        var fake = new FakeExtensionAPI();
        fake.messages().add(new Message.Custom("goal",
                                        "{\"objective\":\"restored\",\"status\":\"active\",\"token_budget\":0,\"completion_criterion\":\"\",\"continue_on_idle\":false,\"input_tokens_at_start\":0,\"output_tokens_at_start\":0,\"started_at_ms\":1000,\"total_paused_ms\":0,\"paused_at_ms\":-1,\"progress\":[]}"));
        new GoalExtension().init(fake);
        fake.fireSessionReady();

        var get = execute(fake.tool("get_goal"), Map.of());
        assertTrue(get.content().contains("restored"));
    }

    @Test
    void sessionReadyRestoresClearedGoalAsIdle() {
        var fake = new FakeExtensionAPI();
        fake.messages().add(new Message.Custom("goal",
                                        "{\"objective\":\"old\",\"status\":\"active\",\"token_budget\":0,\"completion_criterion\":\"\",\"continue_on_idle\":false,\"input_tokens_at_start\":0,\"output_tokens_at_start\":0,\"started_at_ms\":1000,\"total_paused_ms\":0,\"paused_at_ms\":-1,\"progress\":[]}"));
        fake.messages().add(new Message.Custom("goal", "{\"status\":\"idle\"}"));
        new GoalExtension().init(fake);
        fake.fireSessionReady();

        var get = execute(fake.tool("get_goal"), Map.of());
        assertTrue(get.content().contains("status: idle"));
    }

    private static Tool.ToolResult execute(Tool.ToolDefinition tool, Map<String, Object> args) {
        return tool.execute().execute("id", args, ".").join();
    }

    // ---- fakes ----

    private static final class FakeExtensionAPI implements ExtensionAPI {
        private final List<Tool.ToolDefinition> tools = new ArrayList<>();
        private final List<SlashCommand> slashCommands = new ArrayList<>();
        private final List<Supplier<List<String>>> statusLines = new ArrayList<>();
        private final List<Message> messages = new ArrayList<>();
        private final List<Message.Custom> customMessages = new ArrayList<>();
        private final List<String> userMessages = new ArrayList<>();
        private final AtomicReference<String> status = new AtomicReference<>();
        private final FakeRuntime runtime = new FakeRuntime();
        private final List<BiConsumer<ExtensionEvent.TurnStart, ExtensionContext>> turnStartHandlers = new ArrayList<>();
        private final List<BiConsumer<ExtensionEvent.TurnEnd, ExtensionContext>> turnEndHandlers = new ArrayList<>();
        private final List<BiConsumer<ExtensionEvent.SessionReady, ExtensionContext>> sessionReadyHandlers = new ArrayList<>();

        @Override
        public ExtensionAPI onSessionStart(BiConsumer<ExtensionEvent.SessionStart, ExtensionContext> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onSessionReady(BiConsumer<ExtensionEvent.SessionReady, ExtensionContext> handler) {
            sessionReadyHandlers.add(handler);
            return this;
        }

        @Override
        public ExtensionAPI onSessionShutdown(BiConsumer<ExtensionEvent.SessionShutdown, ExtensionContext> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onBeforeAgentStart(BiConsumer<ExtensionEvent.BeforeAgentStart, ExtensionContext> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onAgentStart(BiConsumer<ExtensionEvent.AgentStart, ExtensionContext> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onAgentEnd(BiConsumer<ExtensionEvent.AgentEnd, ExtensionContext> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onTurnStart(BiConsumer<ExtensionEvent.TurnStart, ExtensionContext> handler) {
            turnStartHandlers.add(handler);
            return this;
        }

        @Override
        public ExtensionAPI onTurnEnd(BiConsumer<ExtensionEvent.TurnEnd, ExtensionContext> handler) {
            turnEndHandlers.add(handler);
            return this;
        }

        @Override
        public ExtensionAPI onMessageStart(BiConsumer<ExtensionEvent.MessageStart, ExtensionContext> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onMessageUpdate(BiConsumer<ExtensionEvent.MessageUpdate, ExtensionContext> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onMessageEnd(BiConsumer<ExtensionEvent.MessageEnd, ExtensionContext> handler) {
            return this;
        }

        @Override
        public ExtensionAPI registerMessageRenderer(
                                        BiFunction<Message, ExtensionContext, ExtensionResult.MessageRender> renderer) {
            return this;
        }

        @Override
        public ExtensionAPI onSessionBeforeCompact(
                                        BiConsumer<ExtensionEvent.SessionBeforeCompact, ExtensionContext> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onSessionCompact(BiConsumer<ExtensionEvent.SessionCompact, ExtensionContext> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onToolCall(BiFunction<ExtensionEvent.ToolCall, ExtensionContext, ExtensionResult.Block> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onToolResult(BiConsumer<ExtensionEvent.ToolResult, ExtensionContext> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onToolExecutionStart(
                                        BiConsumer<ExtensionEvent.ToolExecutionStart, ExtensionContext> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onToolExecutionEnd(BiConsumer<ExtensionEvent.ToolExecutionEnd, ExtensionContext> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onBeforeProviderRequest(
                                        BiConsumer<ExtensionEvent.BeforeProviderRequest, ExtensionContext> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onModelSelect(BiConsumer<ExtensionEvent.ModelSelect, ExtensionContext> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onUserBash(BiFunction<ExtensionEvent.UserBash, ExtensionContext, ExtensionResult.UserBashResult> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onBashToolCall(
                                        BiFunction<ExtensionEvent.BashToolCall, ExtensionContext, ExtensionResult.BashToolCallResult> handler) {
            return this;
        }

        @Override
        public ExtensionAPI onInput(BiFunction<String, ExtensionContext, ExtensionResult.InputResult> handler) {
            return this;
        }

        @Override
        public void registerTool(Tool.ToolDefinition tool) {
            tools.add(tool);
        }

        @Override
        public void registerSlashCommand(SlashCommand command) {
            slashCommands.add(command);
        }

        @Override
        public void registerKeybinding(ExtensionKeybinding keybinding) {}

        @Override
        public void registerSkills(List<SkillLoader.Skill> skills) {}

        @Override
        public void registerStatusLines(Supplier<List<String>> lines) {
            statusLines.add(lines);
        }

        @Override
        public void registerFooterLines(Supplier<List<String>> lines) {}

        @Override
        public void registerSystemPrompt(Supplier<String> prompt) {}

        @Override
        public void registerThinkingHeaderText(Supplier<String> text) {}

        @Override
        public void sendMessage(Message.Custom custom) {
            customMessages.add(custom);
            messages.add(custom);
        }

        @Override
        public void sendUserMessage(String content) {
            userMessages.add(content);
        }

        @Override
        public void sendUserMessage(String content, String deliverAs) {
            userMessages.add(content);
        }

        @Override
        public ExtensionRuntime runtime() {
            return runtime;
        }

        Tool.ToolDefinition tool(String name) {
            return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
        }

        SlashCommand slashCommand(String name) {
            return slashCommands.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
        }

        String lastCustomMessage() {
            return customMessages.isEmpty() ? "" : customMessages.getLast().content();
        }

        String status() {
            String s = status.get();
            if (s == null && !statusLines.isEmpty())
                return String.join("\n", statusLines.getFirst().get());
            return s == null ? "" : s;
        }

        List<Message> messages() {
            return messages;
        }

        List<String> userMessages() {
            return userMessages;
        }

        FakeLlm llm() {
            return runtime.llm;
        }

        void fireTurnStart(int turnNumber) {
            var ev = new ExtensionEvent.TurnStart(turnNumber);
            for (var h : turnStartHandlers)
                h.accept(ev, null);
        }

        void fireTurnEnd(int turnNumber) {
            var ev = new ExtensionEvent.TurnEnd(turnNumber);
            for (var h : turnEndHandlers)
                h.accept(ev, null);
        }

        void fireSessionReady() {
            var ev = new ExtensionEvent.SessionReady();
            for (var h : sessionReadyHandlers)
                h.accept(ev, null);
        }

        private final class FakeRuntime implements ExtensionRuntime {
            private final FakeLlm llm = new FakeLlm();

            @Override
            public ai.pijota.llm.LlmClient llm() {
                return llm;
            }

            @Override
            public Path cwd() {
                return Path.of(".");
            }

            @Override
            public String systemPrompt() {
                return "";
            }

            @Override
            public List<Message> messages() {
                return new ArrayList<>(messages);
            }

            @Override
            public ai.pijota.tools.ToolRegistry tools() {
                return null;
            }

            @Override
            public List<Tool.ToolInfo> activeTools() {
                return List.of();
            }

            @Override
            public void setActiveTools(List<String> toolNames) {}

            @Override
            public List<Tool.ToolInfo> allTools() {
                return List.of();
            }

            @Override
            public String sessionName() {
                return "";
            }

            @Override
            public void setSessionName(String name) {}

            @Override
            public boolean isIdle() {
                return true;
            }

            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public void abort() {}

            @Override
            public void compact(String instructions) {}

            @Override
            public void setStatus(String message) {
                status.set(message);
            }

            @Override
            public void appendStatus(String message) {}

            @Override
            public void setStatus(String key, String message) {}

            @Override
            public void clearStatus(String key) {}

            @Override
            public boolean getFlag(String name, boolean defaultValue) {
                return defaultValue;
            }

            @Override
            public java.util.Optional<String> getFlag(String name) {
                return java.util.Optional.empty();
            }

            @Override
            public ExecResult exec(List<String> command) {
                return new ExecResult(1, "", "");
            }
        }
    }

    private static final class FakeLlm implements LlmClient {
        private final AtomicLong input = new AtomicLong();
        private final AtomicLong output = new AtomicLong();

        void setTokens(long in, long out) {
            input.set(in);
            output.set(out);
        }

        @Override
        public long totalInputTokens() {
            return input.get();
        }

        @Override
        public long totalOutputTokens() {
            return output.get();
        }

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public String model() {
            return "fake";
        }

        @Override
        public boolean streamsWithTools() {
            return false;
        }

        @Override
        public void abort() {}

        @Override
        public CompletableFuture<StreamResult> streamMessages(List<Message> messages, List<Tool> tools,
                                        BiConsumer<String, String> onPartial) {
            return CompletableFuture.completedFuture(new StreamResult());
        }

        @Override
        public void close() {}
    }

    @Test
    void statusLabelMapsAllStatuses() {
        assertEquals("active", GoalExtension.statusLabel(GoalState.Status.ACTIVE));
        assertEquals("paused", GoalExtension.statusLabel(GoalState.Status.PAUSED));
        assertEquals("complete", GoalExtension.statusLabel(GoalState.Status.COMPLETE));
        assertEquals("budget-limited", GoalExtension.statusLabel(GoalState.Status.BUDGET_LIMITED));
        assertEquals("idle", GoalExtension.statusLabel(GoalState.Status.IDLE));
    }

    @Test
    void parseObjectiveSplitsOnPipe() {
        var result = GoalExtension.parseObjective("Do something | when done");
        assertEquals("Do something", result[0]);
        assertEquals("when done", result[1]);
    }

    @Test
    void parseObjectiveNoCriterion() {
        var result = GoalExtension.parseObjective("Just an objective");
        assertEquals("Just an objective", result[0]);
        assertEquals("", result[1]);
    }

    @Test
    void parseObjectiveTrimsWhitespace() {
        var result = GoalExtension.parseObjective("  goal  |  criterion  ");
        assertEquals("goal", result[0]);
        assertEquals("criterion", result[1]);
    }
}
