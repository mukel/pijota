package ai.pijota.extensions;

import ai.pijota.agent.AgentEvent;
import ai.pijota.extensions.skills.SkillLoader;
import ai.pijota.llm.LlmClient;
import ai.pijota.messages.Message;
import ai.pijota.modes.SlashCommand;
import ai.pijota.tools.Tool;
import ai.pijota.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionRegistryTest {

    @TempDir
    Path tempDir;

    // ---- helpers ----

    static Extension simple(String name, Consumer<ExtensionAPI> init) {
        return new Extension() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public void init(ExtensionAPI api) {
                init.accept(api);
            }
        };
    }

    // ---- fakes ----

    static class CapturingExtension implements Extension {
        final String name;
        final List<String> events = new ArrayList<>();
        ExtensionAPI api;
        ExtensionRuntime runtime;
        HeadlessToolContext headless;

        CapturingExtension(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "fake " + name;
        }

        @Override
        public void init(ExtensionAPI api) {
            this.api = api;
            events.add("init");
            api.registerSlashCommand(new SlashCommand("fake-" + name, "fake", List.of(), _ -> {}));
            api.registerKeybinding(new ExtensionKeybinding(new KeyChord(KeyChord.Key.A), "fake", () -> {}));
            api.onSessionStart((_, _) -> events.add("session_start"));
            api.onSessionReady((_, _) -> events.add("session_ready"));
            api.onSessionShutdown((_, _) -> events.add("session_shutdown"));
            api.onBeforeAgentStart((_, _) -> events.add("before_agent_start"));
            api.onAgentStart((_, _) -> events.add("agent_start"));
            api.onAgentEnd((_, _) -> events.add("agent_end"));
            api.onTurnStart((_, _) -> events.add("turn_start"));
            api.onTurnEnd((_, _) -> events.add("turn_end"));
            api.onMessageStart((_, _) -> events.add("message_start"));
            api.onMessageUpdate((_, _) -> events.add("message_update"));
            api.onMessageEnd((_, _) -> events.add("message_end"));
            api.onToolCall((tc, _) -> null);
            api.onToolResult((_, _) -> events.add("tool_result"));
            api.onToolExecutionStart((_, _) -> events.add("tool_execution_start"));
            api.onToolExecutionEnd((_, _) -> events.add("tool_execution_end"));
            api.onBeforeProviderRequest((_, _) -> events.add("before_provider_request"));
            api.onModelSelect((_, _) -> events.add("model_select"));
            api.onUserBash((_, _) -> ExtensionResult.UserBashResult.allow());
            api.onBashToolCall((_, _) -> ExtensionResult.BashToolCallResult.allow());
            api.onInput((text, _) -> new ExtensionResult.InputResult(text, false));
            api.onSessionBeforeCompact((_, _) -> events.add("session_before_compact"));
            api.onSessionCompact((_, _) -> events.add("session_compact"));
            api.registerStatusLines(() -> List.of("status-" + name));
            api.registerFooterLines(() -> List.of("footer-" + name));
            api.registerSystemPrompt(() -> "prompt-" + name);
            api.registerThinkingHeaderText(() -> "thinking-" + name);
        }

        @Override
        public void onReady(ExtensionRuntime runtime) {
            this.runtime = runtime;
            events.add("ready");
        }

        @Override
        public void onShutdown(ExtensionRuntime runtime) {
            this.runtime = runtime;
            events.add("shutdown");
        }

        @Override
        public void contributeHeadlessTools(HeadlessToolContext ctx) {
            this.headless = ctx;
            events.add("headless");
        }
    }

    static class BlockingExtension implements Extension {
        @Override
        public String name() {
            return "blocker";
        }

        @Override
        public String description() {
            return "blocks bash";
        }

        @Override
        public void init(ExtensionAPI api) {
            api.onToolCall((tc, _) -> "bash".equals(tc.toolName()) ? new ExtensionResult.Block("blocked") : null);
            api.onUserBash((_, _) -> ExtensionResult.UserBashResult.block("no"));
            api.onBashToolCall((_, _) -> ExtensionResult.BashToolCallResult.block("no"));
            api.onInput((text, _) -> new ExtensionResult.InputResult(text.toUpperCase(), true));
            api.registerMessageRenderer((msg, _) -> new ExtensionResult.MessageRender(List.of("rendered")));
        }
    }

    static class FailingExtension implements Extension {
        @Override
        public String name() {
            return "failing";
        }

        @Override
        public String description() {
            return "fails";
        }

        @Override
        public void init(ExtensionAPI api) {
            throw new RuntimeException("init failure");
        }

        @Override
        public void onReady(ExtensionRuntime runtime) {
            throw new RuntimeException("ready failure");
        }

        @Override
        public void onShutdown(ExtensionRuntime runtime) {
            throw new RuntimeException("shutdown failure");
        }
    }

    static class FakeAgentContext implements AgentContext {
        final List<Message> messages = new ArrayList<>();
        final List<Message.Custom> customMessages = new ArrayList<>();
        final List<String> sent = new ArrayList<>();
        boolean busy;
        boolean closed;
        boolean aborted;
        boolean compacted;

        @Override
        public LlmClient llm() {
            return null;
        }

        @Override
        public boolean isBusy() {
            return busy;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void abort() {
            aborted = true;
        }

        @Override
        public List<Message> messages() {
            return List.copyOf(messages);
        }

        @Override
        public void compact(String instructions) {
            compacted = true;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public void sendUserMessage(String content) {
            sent.add(content);
        }

        @Override
        public void sendUserMessage(String content, String deliverAs) {
            sent.add(content + "|" + deliverAs);
        }

        @Override
        public void addCustomMessage(Message.Custom message) {
            customMessages.add(message);
        }
    }

    static class FakeUiContext implements UiContext {
        final List<String> statuses = new ArrayList<>();

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }

        @Override
        public void appendStatus(String message) {
            statuses.add("append:" + message);
        }

        @Override
        public void setStatus(String key, String message) {
            statuses.add(key + "=" + message);
        }

        @Override
        public void clearStatus(String key) {
            statuses.add("clear:" + key);
        }
    }

    static class FakeLlmClient implements LlmClient {
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
        public CompletableFuture<ai.pijota.llm.StreamResult> streamMessages(List<Message> messages, List<Tool> tools,
                                        BiConsumer<String, String> onPartial) {
            return CompletableFuture.completedFuture(new ai.pijota.llm.StreamResult());
        }
    }

    ExtensionRegistry registry(AgentContext agent, UiContext ui) {
        var r = new ExtensionRegistry();
        r.setAgentLoop(agent);
        r.setTui(ui);
        r.setCwd(tempDir);
        return r;
    }

    // ---- registration & lifecycle ----

    @Test
    void registerCallsInit() {
        var ext = new CapturingExtension("x");
        var reg = new ExtensionRegistry();
        reg.register(ext);
        assertTrue(ext.events.contains("init"));
    }

    @Test
    void registerFailingExtensionDoesNotPropagate() {
        var reg = new ExtensionRegistry();
        assertDoesNotThrow(() -> reg.register(new FailingExtension()));
    }

    @Test
    void readyFiresSessionReadyAndOnReady() {
        var ext = new CapturingExtension("x");
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(ext);
        reg.ready();
        assertTrue(ext.events.contains("session_ready"));
        assertTrue(ext.events.contains("ready"));
    }

    @Test
    void readyFailingExtensionDoesNotPropagate() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(new FailingExtension());
        assertDoesNotThrow(reg::ready);
    }

    @Test
    void cleanupFiresShutdownAndOnShutdown() {
        var ext = new CapturingExtension("x");
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(ext);
        reg.ready();
        reg.cleanup();
        assertTrue(ext.events.contains("session_shutdown"));
        assertTrue(ext.events.contains("shutdown"));
    }

    @Test
    void cleanupFailingExtensionDoesNotPropagate() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(new FailingExtension());
        assertDoesNotThrow(reg::cleanup);
    }

    @Test
    void contributeHeadlessToolsCallsExtensions() {
        var ext = new CapturingExtension("x");
        var reg = new ExtensionRegistry();
        reg.register(ext);
        var ctx = new HeadlessToolContext(List.of("read"), 0, null, null, "sys", tempDir);
        reg.contributeHeadlessTools(ctx);
        assertTrue(ext.events.contains("headless"));
        assertSame(ctx, ext.headless);
    }

    // ---- event dispatch ----

    @Test
    void fireEventDoesNothingWhenAgentClosed() {
        var agent = new FakeAgentContext();
        agent.closed = true;
        var ext = new CapturingExtension("x");
        var reg = registry(agent, new FakeUiContext());
        reg.register(ext);
        reg.fire(new ExtensionEvent.SessionStart(tempDir));
        assertFalse(ext.events.contains("session_start"));
    }

    @Test
    void fireEventDispatchesToRegisteredHandlers() {
        var ext = new CapturingExtension("x");
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(ext);
        reg.fire(new ExtensionEvent.SessionStart(tempDir));
        assertTrue(ext.events.contains("session_start"));
    }

    @Test
    void fireEventHandlerExceptionDoesNotPropagate() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(simple("throws", api -> api.onSessionStart((_, _) -> {
            throw new RuntimeException("boom");
        })));
        assertDoesNotThrow(() -> reg.fire(new ExtensionEvent.SessionStart(tempDir)));
    }

    @Test
    void onAgentEventDispatchesTurnAndMessageEvents() {
        var ext = new CapturingExtension("x");
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(ext);
        reg.onAgentEvent(new AgentEvent.TurnStart(1));
        reg.onAgentEvent(new AgentEvent.TurnEnd(1));
        reg.onAgentEvent(new AgentEvent.MessageStreamStart("assistant"));
        reg.onAgentEvent(new AgentEvent.MessageStreamDelta("hi"));
        reg.onAgentEvent(new AgentEvent.MessageStreamEnd("assistant"));
        assertTrue(ext.events.contains("turn_start"));
        assertTrue(ext.events.contains("turn_end"));
        assertTrue(ext.events.contains("message_start"));
        assertTrue(ext.events.contains("message_update"));
        assertTrue(ext.events.contains("message_end"));
    }

    @Test
    void onAgentEventUnknownEventIgnored() {
        var ext = new CapturingExtension("x");
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(ext);
        reg.onAgentEvent(new AgentEvent.CompactionStream("x", "y"));
        // no new events, just ensure no exception
        assertTrue(ext.events.contains("init"));
    }

    // ---- tool events ----

    @Test
    void beforeToolCallReturnsEmptyWhenNotBlocked() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        assertEquals(Optional.empty(), reg.beforeToolCall("id", "bash", "{}"));
    }

    @Test
    void beforeToolCallReturnsReasonWhenBlocked() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(new BlockingExtension());
        var reason = reg.beforeToolCall("id", "bash", "{}");
        assertTrue(reason.isPresent());
        assertEquals("blocked", reason.get());
    }

    @Test
    void beforeToolCallHandlerExceptionDoesNotPropagate() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(simple("thrower", api -> api.onToolCall((_, _) -> {
            throw new RuntimeException("boom");
        })));
        assertDoesNotThrow(() -> reg.beforeToolCall("id", "bash", "{}"));
    }

    @Test
    void afterToolCallFiresToolResult() {
        var ext = new CapturingExtension("x");
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(ext);
        reg.afterToolCall("id", "bash", Tool.ToolResult.success("ok"));
        assertTrue(ext.events.contains("tool_result"));
    }

    @Test
    void fireToolExecutionStartAndEnd() {
        var ext = new CapturingExtension("x");
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(ext);
        reg.fireToolExecutionStart("id", "bash", "{}");
        reg.fireToolExecutionEnd("id", "bash", Tool.ToolResult.success("ok"), false);
        assertTrue(ext.events.contains("tool_execution_start"));
        assertTrue(ext.events.contains("tool_execution_end"));
    }

    // ---- provider/model events ----

    @Test
    void fireBeforeProviderRequest() {
        var ext = new CapturingExtension("x");
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(ext);
        reg.fireBeforeProviderRequest(new ArrayList<>(List.of(new Message.User("hi"))));
        assertTrue(ext.events.contains("before_provider_request"));
    }

    @Test
    void fireModelSelect() {
        var ext = new CapturingExtension("x");
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(ext);
        reg.fireModelSelect("gpt-4", "gpt-3", "user");
        assertTrue(ext.events.contains("model_select"));
    }

    // ---- user bash ----

    @Test
    void fireUserBashAllowsByDefault() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        var result = reg.fireUserBash("ls", false);
        assertFalse(result.blocked());
    }

    @Test
    void fireUserBashBlockedByExtension() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(new BlockingExtension());
        var result = reg.fireUserBash("ls", false);
        assertTrue(result.blocked());
        assertEquals("no", result.reason());
    }

    @Test
    void fireBashToolCallAllowsByDefault() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        var result = reg.fireBashToolCall("id", "ls", "/tmp", 60);
        assertFalse(result.blocked());
    }

    @Test
    void fireBashToolCallBlockedByExtension() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(new BlockingExtension());
        var result = reg.fireBashToolCall("id", "ls", "/tmp", 60);
        assertTrue(result.blocked());
    }

    // ---- input filtering ----

    @Test
    void filterInputReturnsOriginalWhenNoHandlers() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        assertEquals("hello", reg.filterInput("hello"));
    }

    @Test
    void filterInputTransformsText() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(new BlockingExtension());
        assertEquals("HELLO", reg.filterInput("hello"));
    }

    @Test
    void filterInputStopsWhenConsumed() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(simple("consumer", api -> api
                                        .onInput((text, _) -> new ExtensionResult.InputResult(text + "-a", true))));
        reg.register(simple("ignored", api -> api
                                        .onInput((text, _) -> new ExtensionResult.InputResult(text + "-b", false))));
        assertEquals("hello-a", reg.filterInput("hello"));
    }

    // ---- before agent start ----

    @Test
    void fireBeforeAgentStartAppendsPromptContributions() {
        var ext = new CapturingExtension("x");
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(ext);
        var prompt = reg.fireBeforeAgentStart(List.of(), "base");
        assertTrue(prompt.contains("base"));
        assertTrue(prompt.contains("prompt-x"));
    }

    @Test
    void fireBeforeAgentStartHandlesNullSystemPrompt() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        assertEquals("", reg.fireBeforeAgentStart(List.of(), null));
    }

    @Test
    void fireAgentStartAndEnd() {
        var ext = new CapturingExtension("x");
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(ext);
        reg.fireAgentStart();
        reg.fireAfterAgentEnd();
        assertTrue(ext.events.contains("agent_start"));
        assertTrue(ext.events.contains("agent_end"));
    }

    @Test
    void fireSessionCompactEvents() {
        var ext = new CapturingExtension("x");
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(ext);
        reg.fireSessionBeforeCompact(false, "compact");
        reg.fireSessionCompact();
        assertTrue(ext.events.contains("session_before_compact"));
        assertTrue(ext.events.contains("session_compact"));
    }

    // ---- collections ----

    @Test
    void slashCommandsSkillsKeybindingsCollected() {
        var ext = new CapturingExtension("x");
        var reg = new ExtensionRegistry();
        reg.register(ext);
        assertEquals(1, reg.slashCommands().size());
        assertEquals("fake-x", reg.slashCommands().get(0).name());
        assertEquals(1, reg.keybindings().size());
        assertTrue(reg.skills().isEmpty());
    }

    @Test
    void statusLinesIncludeStatusAndFooterAndKeyed() {
        var ext = new CapturingExtension("x");
        var ui = new FakeUiContext();
        var reg = registry(new FakeAgentContext(), ui);
        reg.register(ext);
        reg.api().runtime().setStatus("key", "value");
        var lines = reg.statusLines();
        assertTrue(lines.contains("status-x"));
        assertTrue(lines.contains("footer-x"));
        assertTrue(lines.contains("value"));
    }

    @Test
    void thinkingHeaderTextReturnsFirstNonBlank() {
        var ext = new CapturingExtension("x");
        var reg = new ExtensionRegistry();
        reg.register(ext);
        assertEquals("thinking-x", reg.thinkingHeaderText());
    }

    @Test
    void thinkingHeaderTextReturnsNullWhenBlank() {
        var reg = new ExtensionRegistry();
        reg.register(simple("blank", api -> api.registerThinkingHeaderText(() -> "  ")));
        assertNull(reg.thinkingHeaderText());
    }

    @Test
    void systemPromptContributions() {
        var ext = new CapturingExtension("x");
        var reg = new ExtensionRegistry();
        reg.register(ext);
        var prompts = reg.systemPromptContributions();
        assertEquals(1, prompts.size());
        assertEquals("prompt-x", prompts.get(0));
    }

    // ---- message renderers ----

    @Test
    void hasMessageRenderersFalseByDefault() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        assertFalse(reg.hasMessageRenderers());
    }

    @Test
    void renderMessageReturnsRenderedLines() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(new BlockingExtension());
        assertTrue(reg.hasMessageRenderers());
        var rendered = reg.renderMessage(new Message.User("hi"));
        assertTrue(rendered.isPresent());
        assertEquals(List.of("rendered"), rendered.get());
    }

    @Test
    void renderMessageSkipsEmptyResult() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        reg.register(simple("empty-render", api -> api.registerMessageRenderer(
                                        (_, _) -> new ExtensionResult.MessageRender(List.of()))));
        assertEquals(Optional.empty(), reg.renderMessage(new Message.User("hi")));
    }

    // ---- ExtensionAPI: registration ----

    @Test
    void apiRegistersToolIntoRegistry() {
        var toolReg = new ToolRegistry();
        var reg = new ExtensionRegistry();
        reg.setToolRegistry(toolReg);
        reg.register(simple("tool-reg", api -> api.registerTool(new Tool.ToolDefinition("custom", "desc", Map.of(),
                                        (id, args, cwd) -> CompletableFuture.completedFuture(
                                                                        Tool.ToolResult.success("ok"))))));
        assertTrue(toolReg.get("custom").isPresent());
    }

    @Test
    void apiRegistersSkills() {
        var reg = new ExtensionRegistry();
        reg.register(simple("skill-reg", api -> api
                                        .registerSkills(List.of(new SkillLoader.Skill("s", "d", "c", null)))));
        assertEquals(1, reg.skills().size());
    }

    @Test
    void apiSendMessageRequiresAgentContext() {
        var agent = new FakeAgentContext();
        var reg = registry(agent, new FakeUiContext());
        reg.register(simple("sender", api -> api.sendMessage(new Message.Custom("type", "content"))));
        assertEquals(1, agent.customMessages.size());
    }

    @Test
    void apiSendUserMessageRequiresAgentContext() {
        var agent = new FakeAgentContext();
        var reg = registry(agent, new FakeUiContext());
        reg.register(simple("sender", api -> {
            api.sendUserMessage("hello");
            api.sendUserMessage("hello", "nextTurn");
        }));
        assertEquals(List.of("hello", "hello|nextTurn"), agent.sent);
    }

    @Test
    void apiRegisterNullsIgnored() {
        var reg = new ExtensionRegistry();
        var api = reg.api();
        assertDoesNotThrow(() -> {
            api.registerMessageRenderer(null);
            api.registerKeybinding(null);
            api.registerStatusLines(null);
            api.registerFooterLines(null);
            api.registerSystemPrompt(null);
            api.registerThinkingHeaderText(null);
            api.registerSkills(null);
        });
        assertTrue(reg.statusLines().isEmpty());
    }

    // ---- ExtensionRuntime ----

    @Test
    void runtimeReflectsState() {
        var agent = new FakeAgentContext();
        var toolReg = new ToolRegistry();
        var ui = new FakeUiContext();
        var reg = registry(agent, ui);
        reg.setToolRegistry(toolReg);
        reg.setSystemPrompt("sys");
        reg.setExtensionFlags(Map.of("a", "true", "b", "false"));
        var rt = reg.api().runtime();

        assertSame(tempDir, rt.cwd());
        assertEquals("sys", rt.systemPrompt());
        assertSame(toolReg, rt.tools());
        assertTrue(rt.activeTools().isEmpty());
        assertTrue(rt.allTools().isEmpty());

        rt.setSessionName("session");
        assertEquals("session", rt.sessionName());

        assertTrue(rt.isIdle());
        agent.busy = true;
        assertFalse(rt.isIdle());

        assertFalse(rt.isClosed());
        agent.closed = true;
        assertTrue(rt.isClosed());

        assertTrue(rt.getFlag("a", false));
        assertFalse(rt.getFlag("b", true));
        assertEquals("true", rt.getFlag("a").orElseThrow());
        assertEquals(Optional.empty(), rt.getFlag("missing"));
    }

    @Test
    void runtimeSetActiveToolsDelegatesToRegistry() {
        var toolReg = new ToolRegistry();
        toolReg.register(new Tool() {
            @Override
            public String name() {
                return "read";
            }

            @Override
            public String description() {
                return "reads";
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of();
            }

            @Override
            public CompletableFuture<ToolResult> execute(String toolCallId, Map<String, Object> arguments,
                                            String sessionCwd) {
                return CompletableFuture.completedFuture(ToolResult.success(""));
            }
        });
        var reg = new ExtensionRegistry();
        reg.setToolRegistry(toolReg);
        reg.api().runtime().setActiveTools(List.of("read"));
        assertEquals(List.of("read"), toolReg.enabledToolNames());
    }

    @Test
    void runtimeSetStatusClearsWhenBlank() {
        var ui = new FakeUiContext();
        var reg = registry(new FakeAgentContext(), ui);
        var rt = reg.api().runtime();
        rt.setStatus("key", "value");
        assertTrue(reg.statusLines().contains("value"));
        rt.setStatus("key", "");
        assertFalse(reg.statusLines().contains("value"));
    }

    @Test
    void runtimeClearStatusNullKeyNoOp() {
        var ui = new FakeUiContext();
        var reg = registry(new FakeAgentContext(), ui);
        var rt = reg.api().runtime();
        assertDoesNotThrow(() -> rt.clearStatus(null));
    }

    @Test
    void runtimeExecRunsCommand() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        var rt = reg.api().runtime();
        var result = rt.exec(List.of("echo", "hello"));
        assertEquals(0, result.exitCode());
        assertTrue(result.success());
        assertTrue(result.stdout().contains("hello"));
    }

    @Test
    void runtimeExecMissingCommandReturnsError() {
        var reg = registry(new FakeAgentContext(), new FakeUiContext());
        var rt = reg.api().runtime();
        var result = rt.exec(List.of("/nonexistent-binary-xyz"));
        assertNotEquals(0, result.exitCode());
        assertFalse(result.success());
    }

    @Test
    void runtimeAbortAndCompactDelegateToAgent() {
        var agent = new FakeAgentContext();
        var reg = registry(agent, new FakeUiContext());
        var rt = reg.api().runtime();
        rt.abort();
        rt.compact("compact");
        assertTrue(agent.aborted);
        assertTrue(agent.compacted);
    }

    @Test
    void runtimeMessagesWhenAgentNull() {
        var reg = new ExtensionRegistry();
        assertTrue(reg.api().runtime().messages().isEmpty());
    }

    @Test
    void runtimeLlmPrefersAgentContext() {
        var client = new FakeLlmClient();
        var agent = new FakeAgentContext() {
            @Override
            public LlmClient llm() {
                return client;
            }
        };
        var reg = registry(agent, new FakeUiContext());
        assertSame(client, reg.api().runtime().llm());
    }

    // ---- directory loading ----

    @Test
    void loadFromDirectoryIgnoresMissingDir() {
        var reg = new ExtensionRegistry();
        assertDoesNotThrow(() -> reg.loadFromDirectory(Path.of("/does/not/exist/xyz")));
    }

    @Test
    void loadFromDirectoryIgnoresNonClassNonJar() throws Exception {
        var reg = new ExtensionRegistry();
        Files.writeString(tempDir.resolve("readme.txt"), "hi");
        assertDoesNotThrow(() -> reg.loadFromDirectory(tempDir));
    }

    @Test
    void setExtensionFlagsNullBecomesEmpty() {
        var reg = new ExtensionRegistry();
        reg.setExtensionFlags(null);
        assertFalse(reg.api().runtime().getFlag("x").isPresent());
    }

    @Test
    void apiReturnsSameInstance() {
        var reg = new ExtensionRegistry();
        assertSame(reg.api(), reg.api());
    }

    @Test
    void registerSlashCommandCaptured() {
        var reg = new ExtensionRegistry();
        reg.register(simple("cmd", api -> api
                                        .registerSlashCommand(new SlashCommand("/my", "desc", List.of(), _ -> {}))));
        assertEquals("/my", reg.slashCommands().get(0).name());
    }
}
