package ai.pijota.extensions;

import ai.pijota.agent.AgentEvent;
import ai.pijota.extensions.skills.SkillLoader;
import ai.pijota.llm.LlmService;
import ai.pijota.messages.Message;
import ai.pijota.modes.SlashCommand;
import ai.pijota.tools.Tool;
import ai.pijota.tools.ToolRegistry;
import ai.pijota.util.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Core registry that loads, registers, and dispatches to all extensions. Owns the lifecycle: loads extensions from
 * ServiceLoader and a directory, wires them into the agent loop, fires events, dispatches tool-call/user-bash
 * decisions, collects slash commands/keybindings/status lines/skills, and handles shutdown cleanup. This is the
 * host-facing API — extension authors use {@link ExtensionAPI}, not this class.
 */
public final class ExtensionRegistry {
    private static final Logger log = LoggerFactory.getLogger(ExtensionRegistry.class);

    private final List<Extension> extensions = new CopyOnWriteArrayList<>();
    private final List<SlashCommand> slashCommands = new CopyOnWriteArrayList<>();
    private final List<SkillLoader.Skill> skills = new CopyOnWriteArrayList<>();
    private final List<ExtensionKeybinding> keybindings = new CopyOnWriteArrayList<>();
    private final Map<Class<? extends ExtensionEvent>, List<Function<ExtensionEvent, Object>>> listeners = new ConcurrentHashMap<>();
    private final List<Supplier<List<String>>> statusLineProviders = new CopyOnWriteArrayList<>();
    private final List<Supplier<List<String>>> footerLineProviders = new CopyOnWriteArrayList<>();
    private final List<Function<Message, ExtensionResult.MessageRender>> messageRenderers = new CopyOnWriteArrayList<>();
    private final List<ExceptionHandler> exceptionHandlers = new CopyOnWriteArrayList<>();
    private final List<URLClassLoader> extensionClassLoaders = new CopyOnWriteArrayList<>();
    private final Map<String, String> keyedStatuses = new ConcurrentSkipListMap<>();
    private final List<Supplier<String>> systemPromptProviders = new CopyOnWriteArrayList<>();
    private final List<Supplier<String>> thinkingHeaderTextProviders = new CopyOnWriteArrayList<>();
    private final ExtensionAPIImpl api = new ExtensionAPIImpl();
    private final ExtensionRuntimeImpl runtime = new ExtensionRuntimeImpl();
    private ToolRegistry toolRegistry;
    private AgentContext agentContext;
    private LlmService llm;
    private volatile UiContext uiContext; // setTui may be called from a worker (remote-control start); read elsewhere
    private Path cwd;
    private String sessionName;
    private String systemPrompt;
    private Supplier<Path> sessionFileSupplier;
    private Path projectDir;
    private Map<String, String> extensionFlags = Map.of();
    private Map<String, Object> extensionsConfig = Map.of(); // settings.json "extensions": {name: bool|string}

    public ExtensionRegistry() {}

    public void loadServiceLoaderExtensions() {
        try {
            for (var ext : ServiceLoader.load(Extension.class))
                register(ext);
        } catch (Throwable e) {
            log.debug("No extensions found via ServiceLoader: {}", e.getMessage());
        }
    }

    public void loadFromDirectory(Path dir) {
        try {
            if (Files.notExists(dir))
                return;
            List<Path> files;
            try (var stream = Files.list(dir)) {
                files = stream.filter(p -> p.getFileName().toString().endsWith(".class")
                                                || p.getFileName().toString().endsWith(".jar")).toList();
            }
            for (var file : files) {
                try {
                    if (file.getFileName().toString().endsWith(".jar")) {
                        loadJarExtensions(file);
                    } else {
                        loadClassFile(file);
                    }
                } catch (Exception e) {
                    log.warn("Failed to load extension from {}: {}", file, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Error scanning extensions dir {}: {}", dir, e.getMessage());
        }
    }

    private void loadJarExtensions(Path jar) throws Exception {
        var loader = URLClassLoader.newInstance(new URL[] {jar.toUri().toURL()},
                                        Thread.currentThread().getContextClassLoader());
        extensionClassLoaders.add(loader);
        for (var ext : ServiceLoader.load(Extension.class, loader)) {
            register(ext);
        }
    }

    private void loadClassFile(Path classFile) throws Exception {
        String name = classFile.getFileName().toString().replace(".class", "");
        String fullName = "extensions." + name;
        try {
            Class<?> cls = Class.forName(fullName);
            if (Extension.class.isAssignableFrom(cls)) {
                register((Extension) cls.getDeclaredConstructor().newInstance());
            }
        } catch (ClassNotFoundException ignored) {
            log.debug("Extension class not found: {}", fullName);
        }
    }

    public void setToolRegistry(ToolRegistry registry) {
        this.toolRegistry = registry;
    }

    public void setAgentLoop(AgentContext loop) {
        this.agentContext = loop;
    }

    public void setLlm(LlmService l) {
        this.llm = l;
    }

    public void setTui(UiContext t) {
        this.uiContext = t;
        // The interactive /question tool follows the active UI owner — route it to t's askQuestions (null → text
        // fallback).
        ai.pijota.tools.SlashCommandTool.setQuestionHandler(t == null ? null : t::askQuestions);
    }

    public void setCwd(Path c) {
        this.cwd = c;
    }

    public void setSessionFile(Supplier<Path> supplier) {
        this.sessionFileSupplier = supplier;
    }

    public void setProjectDir(Path projectDir) {
        this.projectDir = projectDir;
    }

    public void setSystemPrompt(String sp) {
        this.systemPrompt = sp;
    }

    public void setExtensionFlags(Map<String, String> flags) {
        this.extensionFlags = flags != null ? Map.copyOf(flags) : Map.of();
    }

    /** The {@code settings.json} {@code "extensions"} map (values are Boolean or String); set before registration. */
    public void setExtensionsConfig(Map<String, Object> config) {
        this.extensionsConfig = config != null ? Map.copyOf(config) : Map.of();
    }

    /**
     * Resolves whether an extension is on, with precedence (lowest first): declared {@code defaultEnabled} →
     * {@code settings.json extensions[name]} → CLI {@code --name}/{@code --name=value} (enable) and {@code --no-name}
     * (disable). The flag layer wins, so a per-run flag overrides a persisted setting.
     */
    public boolean isEnabled(String name, boolean defaultEnabled) {
        boolean enabled = defaultEnabled;
        Object setting = extensionsConfig.get(name); // settings.json layer
        if (setting instanceof Boolean b)
            enabled = b;
        else if (setting instanceof String s)
            enabled = true; // a config string (e.g. "firefox") implies enabled
        if (extensionFlags.containsKey(name)) // CLI --name / --name=value
            enabled = ExtensionRuntime.truthy(extensionFlags.get(name)) || !isBoolFlag(extensionFlags.get(name));
        if (ExtensionRuntime.truthy(extensionFlags.get("no-" + name))) // CLI --no-name wins last
            enabled = false;
        return enabled;
    }

    /**
     * True when the flag value is an explicit boolean-ish toggle ("true"/"false"/…); a config value like "firefox" is
     * not.
     */
    private static boolean isBoolFlag(String value) {
        if (value == null)
            return true;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return v.equals("true") || v.equals("false") || v.equals("1") || v.equals("0") || v.equals("yes")
                                        || v.equals("no") || v.equals("on") || v.equals("off");
    }

    public ExtensionAPI api() {
        return api;
    }

    public void register(Extension ext) {
        var reason = ext.unavailableReason();
        if (reason.isPresent()) {
            log.info("Extension {} not loaded: {}", ext.name(), reason.get());
            return;
        }
        if (!isEnabled(ext.name(), ext.enabledByDefault())) {
            log.info("Extension {} disabled by config/flag", ext.name());
            return;
        }
        extensions.add(ext);
        try {
            ext.init(api);
        } catch (Exception e) {
            log.warn("Extension {} init failed: {}", ext.name(), e.getMessage());
        }
        log.info("Registered extension: {}", ext.name());
    }

    private void addListener(Class<? extends ExtensionEvent> eventType, Function<ExtensionEvent, Object> handler) {
        listeners.computeIfAbsent(eventType, _ -> new CopyOnWriteArrayList<>()).add(handler);
    }

    // -- event dispatch --

    public void fire(ExtensionEvent event) {
        if (agentContext == null || agentContext.isClosed())
            return;
        var handlers = listeners.getOrDefault(event.getClass(), List.of());
        for (var handler : handlers) {
            try {
                handler.apply(event);
            } catch (Throwable e) {
                log.warn("Event handler error for {}: {}", event.name(), e.getMessage());
            }
        }
    }

    /** Runs the handlers registered for {@code eventType} and returns the first {@code R} result that vetoes. */
    private <R> Optional<R> firstVeto(ExtensionEvent event, Class<R> type, Predicate<R> veto, String label) {
        for (var handler : listeners.getOrDefault(event.getClass(), List.of())) {
            try {
                Object result = handler.apply(event);
                if (type.isInstance(result) && veto.test(type.cast(result)))
                    return Optional.of(type.cast(result));
            } catch (Exception e) {
                log.warn("{} handler error: {}", label, e.getMessage());
            }
        }
        return Optional.empty();
    }

    public Optional<String> beforeToolCall(String toolCallId, String toolName, String arguments) {
        var event = new ExtensionEvent.ToolCall(toolCallId, toolName, arguments, true);
        return firstVeto(event, ExtensionResult.Block.class, b -> true, "tool_call")
                                        .map(b -> b.reason() != null ? b.reason() : "blocked");
    }

    public void afterToolCall(String toolCallId, String toolName, Tool.ToolResult result) {
        fire(new ExtensionEvent.ToolResult(toolCallId, toolName, result));
    }

    // -- tool lifecycle events --

    public void fireToolExecutionStart(String toolCallId, String toolName, String arguments) {
        fire(new ExtensionEvent.ToolExecutionStart(toolCallId, toolName, arguments));
    }

    public void fireToolExecutionEnd(String toolCallId, String toolName, Tool.ToolResult result, boolean isError) {
        fire(new ExtensionEvent.ToolExecutionEnd(toolCallId, toolName, result, isError));
    }

    // -- provider / model events --

    public void fireBeforeProviderRequest(List<Message> messages) {
        fire(new ExtensionEvent.BeforeProviderRequest(messages));
    }

    public boolean hasMessageRenderers() {
        return !messageRenderers.isEmpty();
    }

    public Optional<List<String>> renderMessage(Message message) {
        for (var renderer : messageRenderers) {
            try {
                var rendered = renderer.apply(message);
                if (rendered != null && !rendered.lines().isEmpty())
                    return Optional.of(rendered.lines());
            } catch (Exception e) {
                log.warn("message renderer error: {}", e.getMessage());
            }
        }
        return Optional.empty();
    }

    public void fireModelSelect(String model, String previousModel, String source) {
        fire(new ExtensionEvent.ModelSelect(model, previousModel, source));
    }

    public ExtensionResult.UserBashResult fireUserBash(String command, boolean excludeFromContext) {
        var event = new ExtensionEvent.UserBash(command, excludeFromContext, cwd != null ? cwd : Path.of(""));
        return firstVeto(event, ExtensionResult.UserBashResult.class, ExtensionResult.UserBashResult::blocked,
                                        "user_bash").orElseGet(ExtensionResult.UserBashResult::allow);
    }

    public ExtensionResult.BashToolCallResult fireBashToolCall(String toolCallId, String command, String workdir,
                                    long timeoutSec) {
        var event = new ExtensionEvent.BashToolCall(toolCallId, command, workdir, timeoutSec);
        return firstVeto(event, ExtensionResult.BashToolCallResult.class, ExtensionResult.BashToolCallResult::blocked,
                                        "bash_tool_call").orElseGet(ExtensionResult.BashToolCallResult::allow);
    }

    // -- user input --

    public String filterInput(String input) {
        String result = input;
        for (var handler : listeners.getOrDefault(ExtensionEvent.Input.class, List.of())) {
            try {
                Object handled = handler.apply(new ExtensionEvent.Input(result));
                if (handled instanceof ExtensionResult.InputResult r) {
                    result = r.text();
                    if (r.consumed())
                        break;
                }
            } catch (Exception e) {
                log.warn("input handler error: {}", e.getMessage());
            }
        }
        return result;
    }

    // -- core event helpers --

    public String fireBeforeAgentStart(List<Message> messages, String systemPrompt) {
        var prompt = new StringBuilder(systemPrompt != null ? systemPrompt : "");
        fire(new ExtensionEvent.BeforeAgentStart(messages, prompt,
                                        toolRegistry != null ? toolRegistry.activeTools() : List.of()));
        return prompt.toString();
    }

    public void fireAgentStart() {
        fire(new ExtensionEvent.AgentStart());
    }

    public void fireAfterAgentEnd() {
        fire(new ExtensionEvent.AgentEnd());
    }

    public void fireSessionBeforeCompact(boolean cancel, String instructions) {
        fire(new ExtensionEvent.SessionBeforeCompact(cancel, instructions));
    }

    public void fireSessionCompact() {
        fire(new ExtensionEvent.SessionCompact());
    }

    // -- custom tools / commands / regions --

    public List<SlashCommand> slashCommands() {
        return List.copyOf(slashCommands);
    }

    public List<SkillLoader.Skill> skills() {
        return List.copyOf(skills);
    }

    public List<ExtensionKeybinding> keybindings() {
        return List.copyOf(keybindings);
    }

    public List<String> statusLines() {
        var lines = new ArrayList<String>();
        for (var provider : statusLineProviders) {
            lines.addAll(provider.get());
        }
        lines.addAll(keyedStatuses.values());
        for (var provider : footerLineProviders) {
            lines.addAll(provider.get());
        }
        return lines;
    }

    public String thinkingHeaderText() {
        for (var provider : thinkingHeaderTextProviders) {
            String text = provider.get();
            if (text != null && !text.isBlank())
                return text;
        }
        return null;
    }

    public void ready() {
        for (var ext : extensions) {
            try {
                ext.onReady(runtime);
            } catch (Exception e) {
                log.warn("Extension {} onReady failed: {}", ext.name(), e.getMessage());
            }
        }
    }

    public List<String> systemPromptContributions() {
        var prompts = new ArrayList<String>();
        for (var provider : systemPromptProviders) {
            String prompt = provider.get();
            if (prompt != null && !prompt.isBlank())
                prompts.add(prompt);
        }
        return prompts;
    }

    public void contributeHeadlessTools(HeadlessToolContext ctx) {
        for (var ext : extensions) {
            try {
                ext.contributeHeadlessTools(ctx);
            } catch (Exception e) {
                log.warn("Extension {} error in contributeHeadlessTools: {}", ext.name(), e.getMessage());
            }
        }
    }

    public List<ExceptionHandler> exceptionHandlers() {
        return List.copyOf(exceptionHandlers);
    }

    /** Dispatches an unhandled exception to the registered handlers, in registration order. */
    public void dispatchException(Throwable t) {
        for (var handler : exceptionHandlers) {
            try {
                handler.onException(t);
            } catch (Exception e) {
                log.warn("Exception handler failed: {}", e.getMessage());
            }
        }
    }

    public void cleanup() {
        for (var ext : extensions) {
            try {
                ext.onShutdown(runtime);
            } catch (Throwable e) {
                log.warn("Extension {} onShutdown failed: {}", ext.name(), e.getMessage());
            }
        }
        for (var loader : extensionClassLoaders) {
            try {
                loader.close();
            } catch (Exception e) {
                log.debug("Extension classloader close failed: {}", e.getMessage());
            }
        }
        extensionClassLoaders.clear();
    }

    public void onAgentEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.TurnStart(var turn) -> fire(new ExtensionEvent.TurnStart(turn));
            case AgentEvent.TurnEnd(var turn) -> fire(new ExtensionEvent.TurnEnd(turn));
            case AgentEvent.MessageStreamStart _ -> fire(new ExtensionEvent.MessageStart());
            case AgentEvent.MessageStreamDelta(var delta) -> fire(new ExtensionEvent.MessageUpdate(delta));
            case AgentEvent.MessageStreamEnd _ -> fire(new ExtensionEvent.MessageEnd());
            default -> {}
        }
    }

    // === ExtensionAPIImpl ===

    private class ExtensionAPIImpl implements ExtensionAPI {

        @Override
        @SuppressWarnings("unchecked")
        public <E extends ExtensionEvent> ExtensionAPI on(Class<E> eventType, Consumer<E> handler) {
            addListener(eventType, e -> {
                handler.accept((E) e);
                return null;
            });
            return this;
        }

        @Override
        public ExtensionAPI registerMessageRenderer(Function<Message, ExtensionResult.MessageRender> renderer) {
            if (renderer != null)
                messageRenderers.add(renderer);
            return this;
        }

        @Override
        public ExtensionAPI onUserBash(Function<ExtensionEvent.UserBash, ExtensionResult.UserBashResult> handler) {
            addListener(ExtensionEvent.UserBash.class, e -> handler.apply((ExtensionEvent.UserBash) e));
            return this;
        }

        @Override
        public ExtensionAPI onBashToolCall(
                                        Function<ExtensionEvent.BashToolCall, ExtensionResult.BashToolCallResult> handler) {
            addListener(ExtensionEvent.BashToolCall.class, e -> handler.apply((ExtensionEvent.BashToolCall) e));
            return this;
        }

        @Override
        public ExtensionAPI onToolCall(Function<ExtensionEvent.ToolCall, ExtensionResult.Block> handler) {
            addListener(ExtensionEvent.ToolCall.class, e -> handler.apply((ExtensionEvent.ToolCall) e));
            return this;
        }

        @Override
        public ExtensionAPI onInput(Function<String, ExtensionResult.InputResult> handler) {
            addListener(ExtensionEvent.Input.class, e -> handler.apply(((ExtensionEvent.Input) e).text()));
            return this;
        }

        @Override
        public void registerTool(Tool tool) {
            if (toolRegistry != null) {
                toolRegistry.register(tool);
            }
        }

        @Override
        public void registerTool(Tool.ToolDefinition tool) {
            if (toolRegistry != null) {
                toolRegistry.register(tool.toTool());
            }
        }

        @Override
        public void registerSlashCommand(SlashCommand command) {
            slashCommands.add(command);
        }

        @Override
        public void registerKeybinding(ExtensionKeybinding keybinding) {
            if (keybinding != null)
                keybindings.add(keybinding);
        }

        @Override
        public void registerSkills(List<SkillLoader.Skill> newSkills) {
            if (newSkills != null)
                skills.addAll(newSkills);
        }

        @Override
        public void registerStatusLines(Supplier<List<String>> lines) {
            if (lines != null)
                statusLineProviders.add(lines);
        }

        @Override
        public void registerFooterLines(Supplier<List<String>> lines) {
            if (lines != null)
                footerLineProviders.add(lines);
        }

        @Override
        public void registerExceptionHandler(ExceptionHandler handler) {
            if (handler != null)
                exceptionHandlers.add(handler);
        }

        @Override
        public void registerSystemPrompt(Supplier<String> prompt) {
            if (prompt != null)
                systemPromptProviders.add(prompt);
        }

        @Override
        public void registerThinkingHeaderText(Supplier<String> text) {
            if (text != null)
                thinkingHeaderTextProviders.add(text);
        }

        @Override
        public void sendMessage(Message.Custom custom) {
            if (agentContext != null) {
                agentContext.addCustomMessage(custom);
            }
        }

        @Override
        public void sendUserMessage(String content) {
            if (agentContext != null) {
                UiContext ui = runtime.ui();
                if (ui != null)
                    ui.submitUserMessage(content);
                else
                    agentContext.sendUserMessage(content);
            }
        }

        @Override
        public void sendUserMessage(String content, DeliverAs deliverAs) {
            if (agentContext != null) {
                agentContext.sendUserMessage(content, deliverAs);
            }
        }

        @Override
        public ExtensionRuntime runtime() {
            return runtime;
        }
    }

    private class ExtensionRuntimeImpl implements ExtensionRuntime {
        @Override
        public ai.pijota.llm.LlmClient llm() {
            return agentContext != null ? agentContext.llm() : llm;
        }

        @Override
        public Path cwd() {
            return cwd;
        }

        @Override
        public Path sessionFile() {
            return sessionFileSupplier != null ? sessionFileSupplier.get() : null;
        }

        @Override
        public Path projectDir() {
            return projectDir != null ? projectDir : cwd;
        }

        @Override
        public String systemPrompt() {
            return systemPrompt;
        }

        @Override
        public ToolRegistry tools() {
            return toolRegistry;
        }

        @Override
        public List<Tool.ToolInfo> activeTools() {
            return toolRegistry != null ? toolRegistry.activeTools() : List.of();
        }

        @Override
        public void setActiveTools(List<String> toolNames) {
            if (toolRegistry != null)
                toolRegistry.enableOnly(toolNames);
        }

        @Override
        public List<Tool.ToolInfo> allTools() {
            return toolRegistry != null ? toolRegistry.allTools() : List.of();
        }

        @Override
        public String sessionName() {
            return sessionName;
        }

        @Override
        public void setSessionName(String name) {
            sessionName = name;
        }

        @Override
        public boolean isIdle() {
            return agentContext != null && !agentContext.isBusy();
        }

        @Override
        public boolean isClosed() {
            return agentContext != null && agentContext.isClosed();
        }

        @Override
        public List<Message> messages() {
            return agentContext != null ? agentContext.messages() : List.of();
        }

        @Override
        public void compact(String instructions) {
            if (agentContext != null)
                agentContext.compact(instructions);
        }

        @Override
        public void abort() {
            if (agentContext != null)
                agentContext.abort();
        }

        @Override
        public void setStatus(String message) {
            if (uiContext != null)
                uiContext.setStatus(message);
        }

        @Override
        public void setStatus(String key, String message) {
            if (key == null || key.isBlank())
                return;
            if (message == null || message.isBlank())
                keyedStatuses.remove(key);
            else
                keyedStatuses.put(key, message);
        }

        @Override
        public ExecResult exec(List<String> command) {
            try {
                var result = ProcessRunner.command(command).run();
                return new ExecResult(result.exitCode(), result.stdout(), result.stderr());
            } catch (Exception e) {
                return new ExecResult(-1, "", e.getMessage());
            }
        }

        @Override
        public void clearStatus(String key) {
            if (key != null)
                keyedStatuses.remove(key);
        }

        @Override
        public boolean getFlag(String name, boolean defaultValue) {
            // CLI flag wins, then settings.json extensions[name] (Boolean or non-blank String = on), else the default.
            String val = extensionFlags != null ? extensionFlags.get(name) : null;
            if (val != null)
                return ExtensionRuntime.truthy(val);
            Object setting = extensionsConfig.get(name);
            if (setting instanceof Boolean b)
                return b;
            if (setting instanceof String s)
                return ExtensionRuntime.truthy(s) || !s.isBlank();
            return defaultValue;
        }

        @Override
        public Optional<String> getFlag(String name) {
            // The config value: CLI --name=value first, then a settings.json string value.
            String cli = extensionFlags != null ? extensionFlags.get(name) : null;
            if (cli != null)
                return Optional.of(cli);
            return extensionsConfig.get(name) instanceof String s ? Optional.of(s) : Optional.empty();
        }

        @Override
        public UiContext ui() {
            return uiContext;
        }
    }

}
