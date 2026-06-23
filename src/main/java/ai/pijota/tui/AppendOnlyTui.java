package ai.pijota.tui;

import ai.pijota.agent.AgentEvent;
import ai.pijota.agent.PijotaSession;
import ai.pijota.llm.ThinkingLevel;
import ai.pijota.llm.ModelInfo;
import ai.pijota.llm.ModelRegistry;
import ai.pijota.extensions.skills.SkillLoader;
import ai.pijota.extensions.ExtensionKeybinding;
import ai.pijota.extensions.KeyChord;
import ai.pijota.extensions.UiContext;
import ai.pijota.config.SettingsManager;
import ai.pijota.messages.Message;
import ai.pijota.modes.SlashCommand;
import ai.pijota.theme.AnsiTheme;
import ai.pijota.tools.SlashCommandTool;
import ai.pijota.tools.Tool;
import ai.pijota.tools.WhoamiTool;
import ai.pijota.util.ErrorUtil;
import ai.pijota.util.Platform;
import ai.pijota.util.ProcessRunner;
import ai.pijota.util.SelfRespawn;
import ai.pijota.util.Util;
import static ai.pijota.util.Util.fmtCount;
import static ai.pijota.util.Util.shortenCwd;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOError;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class AppendOnlyTui implements AutoCloseable, UiContext {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AppendOnlyTui.class);
    private AnsiTheme theme;
    private String provider;
    private String model;
    private String cwd;
    private String rawCwd;
    private final SettingsManager settings;
    private final PijotaSession session;
    private FileReferenceResolver fileRefs;
    private long contextWindow;
    private StreamRenderer streamRenderer;
    private AnsiTheme thinkingTheme;
    private final Terminal terminal;
    private final PromptLayout promptLayout;
    private final TerminalCoordinator terminalUi;
    private final PromptEditor promptEditor;
    private final ScheduledExecutorService animator = Executors.newSingleThreadScheduledExecutor(
                                    Thread.ofPlatform().name("pijota-tui-anim").factory());
    private Thread inputThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final ExecutorService agentExecutor = Executors
                                    .newSingleThreadExecutor(Thread.ofPlatform().name("pijota-tui-agent").factory());
    private volatile Future<?> authTask; // in-flight OAuth login, cancellable via Esc/Ctrl+C
    private final TurnActivity activity = new TurnActivity();
    private final StatusOverride statusOverride = new StatusOverride();
    private final DialogHandler dialogs;
    private final PromptPresenter promptPresenter;
    private final PromptSelector selector = new PromptSelector();
    private volatile boolean ctrlXPrefix;
    private final CtrlXKeymap ctrlXKeymap = new CtrlXKeymap().bind('m', "model", this::showModelSelector)
                                    .bind('l', "sessions", this::showSessionPicker)
                                    .bind('n', "new", this::startNewSession).bind('t', "theme", this::showThemeSelector)
                                    .bind('c', "compact", () -> triggerCompaction(""))
                                    .bind('e', "editor", this::openPromptInEditor)
                                    .bind('o', "logs", this::showLogSelector);
    private final GlobalKeyMap globalKeys = new GlobalKeyMap().bind(Key.Special.CTRL_G, this::openPromptInEditor)
                                    .bind(Key.Special.SHIFT_TAB, this::cycleThinkingLevel)
                                    .bind(Key.Special.CTRL_L, this::showModelSelector)
                                    .bind(Key.Special.CTRL_P, this::showCommandPalette)
                                    .bind(Key.Special.CTRL_SHIFT_P, this::cycleModel);

    @Override
    public void setStatus(String msg) {
        setStatusOverride(msg);
    }

    @Override
    public AnsiTheme theme() {
        return theme;
    }

    void setTheme(AnsiTheme loaded) {
        this.theme = loaded;
        this.thinkingTheme = loaded.thinking();
        terminalUi.setTheme(loaded);
        streamRenderer.setTheme(theme, thinkingTheme);
    }

    @Override
    public CompletableFuture<Boolean> confirm(String question) {
        return dialogs.confirm(question);
    }

    @Override
    public CompletableFuture<String> prompt(String question, String defaultValue) {
        return dialogs.prompt(question, defaultValue);
    }

    @Override
    public void alert(String message) {
        appendPlain(List.of(theme.styled("toolLabel", "alert"), message));
        redrawPrompt();
    }

    @Override
    public <T> void select(String title, List<T> items, java.util.function.Function<T, String> render,
                                    java.util.function.Consumer<T> onChoice) {
        var snapshot = List.copyOf(items);
        selector.show(title, snapshot.stream().map(render).toList(), idx -> onChoice.accept(snapshot.get(idx)));
        redrawPrompt();
    }

    @Override
    public void viewer(String title, List<String> lines) {
        logViewer.show(title, lines);
        redrawPrompt(); // redraw sizes the viewport to fit the screen (see fitViewerViewport)
    }

    @Override
    public void viewer(String title, java.util.function.Supplier<List<String>> source) {
        logViewer.showLive(title, source);
        redrawPrompt();
    }

    public void appendStatus(String msg) {
        appendPlain(List.of(theme.styled("statusText", msg)));
    }

    void appendError(String msg) {
        appendPlain(List.of(theme.styled("toolError", msg)));
    }

    PijotaSession session() {
        return session;
    }

    String sessionName() {
        return sessionName;
    }

    void sessionName(String name) {
        sessionName = name;
    }

    SlashCommand extensionCommand(String cmd) {
        return extensionCommands.get(cmd);
    }

    void stop() {
        running.set(false);
    }

    // True between the first streamed content token and the turn's end. While set, the live-tail row is reserved (shown
    // even when momentarily empty between lines) so the prompt region keeps a constant height and doesn't jitter as
    // lines commit.
    private volatile boolean streamingContent;
    private final DoublePressGate interruptGate = new DoublePressGate(INTERRUPT_WINDOW_MS);
    private final DoublePressGate exitGate = new DoublePressGate(EXIT_WINDOW_MS);
    private ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
    private String sessionName;
    private Attributes originalAttributes;
    private final Terminal.SignalHandler originalIntHandler;
    private final Terminal.SignalHandler originalWinchHandler;
    private final SlashCommandRegistry commands;
    private final TuiCommandController commandController;
    private final Map<String, SlashCommand> extensionCommands;
    private final Map<KeyChord, ExtensionKeybinding> extensionKeybindings;
    /**
     * Per-tool-call state captured at ToolStart, consumed at ToolResult: args for the header + start time for elapsed.
     */
    private record ToolInProgress(Supplier<String> args, long startedAtMs) {
    }

    private final Map<String, ToolInProgress> toolsInProgress = new ConcurrentHashMap<>();
    private final TuiSelectors selectors;
    private volatile boolean showSessionPickerOnStart;
    private final LogViewer logViewer = new LogViewer();
    private final WorkingAnimation animation = new WorkingAnimation();
    private final FollowUpQueue followUps = new FollowUpQueue();
    private final MessageBrowser messageBrowser;
    private PendingEditManager pendingEdit;
    private final Supplier<List<String>> extensionLinesSupplier;
    private final List<SkillLoader.Skill> skills;
    private final ThemeManager themeMgr;
    private final ThemeCommandHandler themeCommands;
    private final ShellCommandRunner shellCommands;
    private final SessionCommands sessionCommands;
    private final ModelCommandHandler modelCommands;
    private final SkillCommandHandler skillCommands;
    private final SettingsCommandHandler settingsCommands;
    private final LoginCommandHandler loginCommands;
    private final CommandPaletteHandler commandPalette;

    public void requestSessionPicker() {
        showSessionPickerOnStart = true;
    }
    private static final long INTERRUPT_WINDOW_MS = 1200;
    private static final long EXIT_WINDOW_MS = 800;
    private static final long TOKEN_STATUS_CACHE_MS = 1000;
    private final ContextTokenMeter tokenMeter;

    public AppendOnlyTui(PijotaSession session, AnsiTheme theme, String provider, String model,
                                    List<SlashCommand> extensionCommands, SettingsManager settings,
                                    List<SkillLoader.Skill> skills, Supplier<List<String>> extensionLinesSupplier,
                                    List<ExtensionKeybinding> extensionKeybindings) throws IOException {
        this.commands = new SlashCommandRegistry(extensionCommands);
        this.promptEditor = new PromptEditor(commands);
        this.messageBrowser = new MessageBrowser(promptEditor, session::userMessages);
        this.session = session;
        this.tokenMeter = new ContextTokenMeter(() -> this.session.messages().size(),
                                        () -> this.session.estimatedContextTokens(), TOKEN_STATUS_CACHE_MS);
        this.theme = theme;
        this.provider = provider;
        this.model = model;
        this.settings = settings;
        if (settings != null) {
            animation.set(settings.workingAnimation());
        }
        this.extensionLinesSupplier = extensionLinesSupplier != null ? extensionLinesSupplier : List::of;
        this.skills = skills;
        Host host = new Host();
        this.dialogs = new DialogHandler(host);
        this.promptPresenter = new PromptPresenter(host, activity, statusOverride, dialogs, animation, followUps,
                                        tokenMeter, extensionLinesSupplier);
        this.shellCommands = new ShellCommandRunner(host,
                                        settings != null ? settings.effectiveShellPath() : Platform.defaultShell());
        this.sessionCommands = new SessionCommands(host);
        this.modelCommands = new ModelCommandHandler(host);
        this.skillCommands = new SkillCommandHandler(host);
        this.settingsCommands = new SettingsCommandHandler(host);
        this.contextWindow = resolveContextWindow(provider, model);
        this.thinkingTheme = theme.thinking();
        this.themeMgr = new ThemeManager(host);
        this.themeCommands = new ThemeCommandHandler(host);
        this.loginCommands = new LoginCommandHandler(host);
        this.commandPalette = new CommandPaletteHandler(host);
        this.terminal = TerminalBuilder.builder().dumb(false).jna(false).jansi(false).build();
        this.originalIntHandler = this.terminal.handle(Terminal.Signal.INT, _ -> handleCtrlC());
        this.originalWinchHandler = this.terminal.handle(Terminal.Signal.WINCH, _ -> handleResize());
        // Confirm callbacks point straight at the owning handlers; session/fork keep TUI-local restore logic.
        this.selectors = new TuiSelectors(modelCommands::confirmModelSelection, this::confirmSessionSelection,
                                        themeCommands::confirmThemeSelection,
                                        settingsCommands::confirmSettingsSelection,
                                        loginCommands::confirmProviderSelection, this::confirmForkSelection,
                                        commandPalette::confirmPaletteSelection);
        var regions = new ArrayList<PromptRegion>();
        regions.add(new PromptRegion.Editor());
        regions.add(new PendingRegion());
        regions.addAll(selectors.regions());
        regions.add(new PromptRegion.Completions());
        regions.add(new PromptSelectorRegion(selector));
        regions.add(new LogViewerRegion(logViewer));
        regions.add(new PromptRegion.StatusBar());
        this.promptLayout = new PromptLayout(regions);
        this.terminalUi = new TerminalCoordinator(terminal, theme, promptLayout);
        this.streamRenderer = new StreamRenderer(terminalUi::appendLines, theme, thinkingTheme,
                                        () -> terminal.getWidth() - 4, this::showThinking, () -> thinkingLevel.label());
        this.extensionCommands = new HashMap<>();
        for (var cmd : extensionCommands) {
            this.extensionCommands.put("/" + cmd.name(), cmd);
            for (String alias : cmd.aliases()) {
                this.extensionCommands.put("/" + alias, cmd);
            }
        }
        this.extensionKeybindings = new HashMap<>();
        for (var binding : extensionKeybindings) {
            if (binding != null && binding.key() != null) {
                this.extensionKeybindings.put(binding.key(), binding);
            }
        }
        this.commandController = new TuiCommandController(this, commands);
        SlashCommandTool.setQuestionHandler(this::askQuestions);
        updateWhoamiTool();
        session.addEventListener(this::onEvent);
    }

    /**
     * The single adapter backing every command handler's narrow {@link TuiHost}-derived view: each handler sees only
     * its declared {@code Host} contract, never this class. Calls that share a name with one of this adapter's own
     * methods are qualified with {@code AppendOnlyTui.this} so they reach the enclosing TUI rather than recursing.
     */
    private final class Host implements DialogHandler.Host, ShellCommandRunner.Host, SessionCommands.Host,
                                    ModelCommandHandler.Host, SkillCommandHandler.Host, SettingsCommandHandler.Host,
                                    ThemeManager.Host, ThemeCommandHandler.Host, LoginCommandHandler.Host,
                                    CommandPaletteHandler.Host, PromptPresenter.Host {

        @Override
        public long contextWindow() {
            return contextWindow;
        }

        @Override
        public String liveStreamTail() {
            return streamingContent ? streamRenderer.liveTail().orElse("") : null;
        }

        // ---- shared primitives (TuiHost) ----
        @Override
        public void appendPlain(List<String> lines) {
            AppendOnlyTui.this.appendPlain(lines);
        }

        @Override
        public void appendError(String message) {
            AppendOnlyTui.this.appendError(message);
        }

        @Override
        public void appendStatus(String message) {
            AppendOnlyTui.this.appendStatus(message);
        }

        @Override
        public String styled(String slot, String text) {
            return theme.styled(slot, text);
        }

        @Override
        public void redrawPrompt() {
            AppendOnlyTui.this.redrawPrompt();
        }

        @Override
        public void clearPrompt() {
            promptEditor.setText("");
        }

        @Override
        public boolean busy() {
            return activity.isBusy();
        }

        @Override
        public long beginBusy(String phase) {
            return AppendOnlyTui.this.beginBusy(phase);
        }

        @Override
        public void endBusy(long generation) {
            AppendOnlyTui.this.endBusy(generation);
        }

        @Override
        public List<String> promptEcho(String text) {
            return AppendOnlyTui.this.promptEcho(text);
        }

        // ---- status / dialog ----
        @Override
        public void setStatus(String status) {
            AppendOnlyTui.this.setStatusOverride(status);
        }

        @Override
        public void clearStatus() {
            AppendOnlyTui.this.clearStatusOverride();
        }

        @Override
        public void setTransientStatus(String status, long millis) {
            AppendOnlyTui.this.setTransientStatus(status, millis);
        }

        // ---- session / runtime accessors ----
        @Override
        public PijotaSession session() {
            return session;
        }

        @Override
        public SettingsManager settings() {
            return settings;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public String model() {
            return model;
        }

        @Override
        public String cwd() {
            return cwd;
        }

        @Override
        public ThinkingLevel thinkingLevel() {
            return thinkingLevel;
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
        public void refreshSessionName() {
            AppendOnlyTui.this.refreshSessionName();
        }

        @Override
        public String tokenStatus() {
            return promptPresenter.tokenStatus();
        }

        @Override
        public CompletableFuture<Boolean> confirm(String question) {
            return AppendOnlyTui.this.confirm(question);
        }

        @Override
        public TuiSelectors selectors() {
            return selectors;
        }

        @Override
        public PromptEditor promptEditor() {
            return promptEditor;
        }

        // ---- shell / agent execution ----
        @Override
        public void fireUserBash(String command, boolean noContext) {
            session.extensionRegistry().fireUserBash(command, noContext);
        }

        @Override
        public void submitAuthTask(Runnable task) {
            authTask = agentExecutor.submit(task);
        }

        @Override
        public void submitAgentPrompt(String prompt, Runnable after) {
            agentExecutor.submit(() -> {
                try {
                    session.prompt(prompt).join();
                } finally {
                    after.run();
                }
            });
        }

        // ---- model ----
        @Override
        public void switchModel(ModelInfo chosen) {
            switchToModel(chosen);
        }

        // ---- terminal control ----
        @Override
        public void showSessionWarning() {
            AppendOnlyTui.this.showSessionWarning();
        }

        // ---- theme ----
        @Override
        public AnsiTheme theme() {
            return theme;
        }

        @Override
        public void setTheme(AnsiTheme newTheme) {
            AppendOnlyTui.this.setTheme(newTheme);
        }

        @Override
        public List<ThemeItem> discoverThemes() {
            return themeMgr.discover();
        }

        @Override
        public List<ThemeItem> themes() {
            return themeMgr.discover();
        }

        @Override
        public void showThemes(List<ThemeItem> items) {
            selectors.show(TuiSelectors.Slot.THEME, items);
        }

        @Override
        public void selectCurrentTheme() {
            selectors.<ThemeItem>selectFirst(TuiSelectors.Slot.THEME, ThemeItem::current);
        }

        @Override
        public java.util.Optional<ThemeItem> selectedTheme() {
            return selectors.selected(TuiSelectors.Slot.THEME);
        }

        @Override
        public void switchToTheme(String name) {
            themeMgr.switchTo(name);
        }

        @Override
        public void switchTheme(String name) {
            themeMgr.switchTo(name);
        }

        @Override
        public void switchToThemePath(String path) {
            themeMgr.switchToPath(path);
        }

        @Override
        public void deactivateThemeSelector() {
            selectors.deactivate(TuiSelectors.Slot.THEME);
        }

        // ---- skills ----
        @Override
        public List<SkillLoader.Skill> skills() {
            return skills;
        }

        // ---- settings ----
        @Override
        public boolean settingsAvailable() {
            return settings != null;
        }

        @Override
        public SettingsCommandHandler.SettingsSnapshot settingsSnapshot() {
            var cfg = settings.config();
            return new SettingsCommandHandler.SettingsSnapshot(settings.contextFilesEnabled(),
                                            settings.compactionEnabled(), settings.shortenPathsEnabled(), thinkingLevel,
                                            cfg.hideThinking(), cfg.quietStartup(), animation.style());
        }

        @Override
        public void showSettings(List<SettingsItem> items) {
            selectors.show(TuiSelectors.Slot.SETTINGS, items);
        }

        @Override
        public java.util.Optional<SettingsItem> selectedSetting() {
            return selectors.selected(TuiSelectors.Slot.SETTINGS);
        }

        @Override
        public void refreshSettings(List<SettingsItem> items) {
            selectors.refresh(TuiSelectors.Slot.SETTINGS, items);
        }

        @Override
        public void toggleCompaction() {
            settings.setCompactionEnabled(!settings.compactionEnabled());
        }

        @Override
        public void setAutoCompactionEnabled(boolean enabled) {
            session.setAutoCompactionEnabled(enabled);
        }

        @Override
        public void toggleContextFiles() {
            settings.setContextFilesEnabled(!settings.contextFilesEnabled());
        }

        @Override
        public void toggleShortenPaths() {
            settings.setShortenPathsEnabled(!settings.shortenPathsEnabled());
        }

        @Override
        public ThinkingLevel advanceThinkingLevel() {
            thinkingLevel = thinkingLevel.next();
            session.setThinkingLevel(thinkingLevel);
            settings.setThinkingLevel(thinkingLevel.name());
            return thinkingLevel;
        }

        @Override
        public void toggleHideThinking() {
            settings.setHideThinking(!settings.config().hideThinking());
        }

        @Override
        public void toggleQuietStartup() {
            settings.setQuietStartup(!settings.config().quietStartup());
        }

        @Override
        public void toggleWorkingAnimation() {
            settings.setWorkingAnimation(animation.toggle());
        }

        // ---- login ----
        @Override
        public void showProviders(List<LoginProviderItem> items) {
            selectors.show(TuiSelectors.Slot.LOGIN, items);
        }

        @Override
        public java.util.Optional<LoginProviderItem> selectedProvider() {
            return selectors.selected(TuiSelectors.Slot.LOGIN);
        }

        @Override
        public void deactivateProviderSelector() {
            selectors.deactivate(TuiSelectors.Slot.LOGIN);
        }

        @Override
        public void setDefaultModelForProvider(String provider, String model) throws IOException {
            settings.setDefaultModelForProvider(provider, model);
        }

        @Override
        public void removeApiKey(String provider) throws IOException {
            settings.removeApiKey(provider);
        }

        // ---- command palette ----
        @Override
        public List<CompletionItem> commands() {
            return commands.completeWithDescriptions("");
        }

        @Override
        public List<ModelInfo> availableModels() {
            return ModelRegistry.KNOWN_MODELS.stream().filter(
                                            m -> settings != null && settings.resolveApiKey(m.provider()) != null)
                                            .toList();
        }

        @Override
        public java.util.Optional<ModelInfo> model(String id) {
            return ModelRegistry.KNOWN_MODELS.stream().filter(m -> m.id().equals(id)).findFirst();
        }

        @Override
        public List<SessionItem> sessions() {
            return SessionItem.fromPaths(session.resumableSessions());
        }

        @Override
        public void showPalette(List<CompletionItem> items) {
            selectors.show(TuiSelectors.Slot.PALETTE, items);
        }

        @Override
        public java.util.Optional<CompletionItem> selectedPaletteItem() {
            return selectors.selected(TuiSelectors.Slot.PALETTE);
        }

        @Override
        public void deactivatePalette() {
            selectors.deactivate(TuiSelectors.Slot.PALETTE);
        }

        @Override
        public void handleCommand(String command) {
            commandController.handle(command);
        }

        @Override
        public void loadSession(String name) {
            AppendOnlyTui.this.loadSession("/load " + name);
        }
    }

    public void run(String version, String cwd) {
        this.rawCwd = cwd;
        this.cwd = shortenCwd(cwd) + gitBranch(cwd);
        this.pendingEdit = new PendingEditManager(cwd);
        this.fileRefs = new FileReferenceResolver(Path.of(cwd));
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "pijota-tui-shutdown"));
        try {
            originalAttributes = terminal.enterRawMode();
            terminal.writer().print("\033[?2004h");
            terminal.writer().flush();
            appendStartupGreeting(version, cwd);
            if (showSessionPickerOnStart) {
                showSessionPicker();
                showSessionPickerOnStart = false;
            }
            terminalUi.start(promptPresenter.decorate(promptEditor.snapshot()));
            animator.scheduleAtFixedRate(this::tickWorkingAnimation, 125, 125, TimeUnit.MILLISECONDS);
            inputThread = Thread.ofPlatform().name("pijota-tui-input").uncaughtExceptionHandler((t, e) -> {
                // Last resort: never leave the terminal in raw mode if the input
                // thread dies unexpectedly — restore it and unblock shutdown.
                restoreTerminalState();
                shutdownLatch.countDown();
            }).start(this::readInputLoop);
            while (running.get()) {
                try {
                    shutdownLatch.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running.set(false);
                }
            }
        } finally {
            close();
        }
    }

    private void appendStartupGreeting(String version, String cwd) {
        if (settings != null && settings.config().quietStartup()) {
            appendPlain(List.of(theme.styled("bannerMeta", "pijota " + version)));
            return;
        }
        var lines = new ArrayList<String>();
        lines.add(theme.styled("bannerName", "pijota " + version) + theme.styled("bannerMeta",
                                        "  " + PromptPresenter.compactModelName(provider, model)));
        lines.add(theme.styled("dim", TuiCopy.START_HINT));

        // recent sessions
        try {
            var items = SessionItem.fromPaths(session.resumableSessions());
            if (!items.isEmpty()) {
                lines.add("");
                lines.add(theme.styled("dim", "recent sessions"));
                int count = 0;
                for (var it : items) {
                    if (count++ >= 3)
                        break;
                    lines.add(theme.styled("statusText", "  " + it.description()) + theme.styled("commandMeta",
                                                    "  " + compactSessionDetail(it.detailText())));
                }
            }
        } catch (Exception ignored) {}
        appendPlain(lines);
    }

    static String compactSessionDetail(String detail) {
        if (detail == null || detail.isBlank())
            return "";
        int uuidStart = detail.lastIndexOf("  ");
        return uuidStart > 0 ? detail.substring(0, uuidStart).stripTrailing() : detail;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true))
            return;
        running.set(false);
        shutdownLatch.countDown();
        if (inputThread != null && inputThread != Thread.currentThread())
            inputThread.interrupt();
        shellCommands.interruptThread();
        agentExecutor.shutdownNow();
        animator.shutdownNow();
        session.extensionRegistry().cleanup();
        if (SelfRespawn.isRespawnInProgress())
            return;
        try {
            terminalUi.close();
        } catch (Exception ignored) {}
        restoreTerminalState();
        SlashCommandTool.setQuestionHandler(null);
        try {
            terminal.close();
        } catch (Exception | IOError ignored) {}
    }

    private void restoreTerminalState() {
        try {
            terminal.writer().print("\033[?2026l\033[?2004l\033[?25h\033[0m");
            terminal.writer().flush();
        } catch (Exception ignored) {}
        try {
            if (originalAttributes != null)
                terminal.setAttributes(originalAttributes);
        } catch (Exception | IOError ignored) {}
        try {
            if (originalIntHandler != null)
                terminal.handle(Terminal.Signal.INT, originalIntHandler);
            if (originalWinchHandler != null)
                terminal.handle(Terminal.Signal.WINCH, originalWinchHandler);
        } catch (Exception ignored) {}
    }

    private void readInputLoop() {
        while (running.get()) {
            if (SelfRespawn.isRespawnInProgress()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            try {
                var key = InputDecoder.readKey(terminal);
                try {
                    if (dialogs.handleConfirm(key))
                        continue;
                    if (dialogs.handlePrompt(key))
                        continue;
                    if (routeModalKey(logViewer::active, logViewer::handleKey, key))
                        continue;
                    // The select picker is modal too: route keys to it before global ESC/etc. so Esc cancels the
                    // picker (and Ctrl+X o's) rather than triggering a generic escape.
                    if (routeModalKey(selector::active, selector::handleKey, key))
                        continue;
                    if (handleExtensionKeybinding(key))
                        continue;
                    if ((key == Key.Special.ESC || key == Key.Special.DOUBLE_ESC) && selectors.anyActive()) {
                        selectors.deactivateAll();
                        clearStatusOverride();
                        promptEditor.setText("");
                        redrawPrompt();
                        continue;
                    }
                    if (key == Key.Special.DOUBLE_ESC) {
                        handleEsc(true);
                        continue;
                    }
                    if (key == Key.Special.ESC) {
                        handleEsc(false);
                        continue;
                    }
                    if (key == Key.Special.CTRL_C) {
                        handleCtrlC();
                        continue;
                    }
                    if (key == Key.Special.CTRL_X) {
                        ctrlXPrefix = true;
                        setStatusOverride(ctrlXKeymap.hint());
                        redrawPrompt();
                        continue;
                    }
                    if (ctrlXPrefix) {
                        if (key == Key.Special.ESC) {
                            ctrlXPrefix = false;
                            clearStatusOverride();
                            redrawPrompt();
                            continue;
                        }
                        if (key instanceof Key.Char c && ctrlXKeymap.dispatch(c.value())) {
                            ctrlXPrefix = false;
                            clearStatusOverride();
                            continue;
                        }
                        ctrlXPrefix = false;
                        clearStatusOverride();
                    }
                    if (globalKeys.dispatch(key))
                        continue;
                    if (key == Key.Special.PASTE_START) {
                        Key paste = InputDecoder.readPaste(terminal);
                        if (paste instanceof Key.Paste p) {
                            promptEditor.insertText(p.text());
                            redrawPrompt();
                        }
                        continue;
                    }
                    if (selectors.handle(key, this::redrawPrompt))
                        continue;
                    if (promptEditor.handleCompletionKey(key)) {
                        redrawPrompt();
                        continue;
                    }
                    if (key == Key.Special.UP && (promptEditor.isEmpty() || promptEditor.isInHistory())) {
                        if (!promptEditor.historyPrev() && !activity.isBusy()) {
                            messageBrowser.enter();
                        }
                        redrawPrompt();
                        continue;
                    }
                    if (key == Key.Special.DOWN && promptEditor.isInHistory()) {
                        promptEditor.historyNext();
                        redrawPrompt();
                        continue;
                    }
                    if (messageBrowser.isActive() && messageBrowser.handleKey(key)) {
                        redrawPrompt();
                        continue;
                    }
                    var result = promptEditor.apply(key);
                    if (result.exit()) {
                        running.set(false);
                        break;
                    }
                    if (result.changed()) {
                        selectors.filter(promptEditor.text());
                        redrawPrompt();
                    }
                    result.submitted().ifPresent(this::handleSubmit);
                } catch (Exception inner) {
                    if (SelfRespawn.isRespawnInProgress())
                        continue;
                    dispatchException(inner);
                    clearStatusOverride();
                    redrawPrompt();
                }
            } catch (Throwable e) {
                // Throwable, not Exception: an Error such as NoClassDefFoundError (e.g. the jar was rebuilt under a
                // running instance, so a lazily-loaded class vanished) must not kill the input thread and strand the
                // terminal in raw mode. Surface it and keep the loop alive so the terminal stays usable and quittable.
                dispatchException(e);
                appendPlain(List.of(theme.styled("toolError", Util.stackTrace(e))));
                setTransientStatus("Error handling input — terminal still active; restart pijota if it persists", 5000);
                redrawPrompt();
            }
        }
    }

    private boolean handleExtensionKeybinding(Key key) {
        if (key instanceof Key.Char)
            return false;
        if (isReservedKey(key))
            return false;
        if (selectors.anyActive() || promptEditor.hasCompletions() || messageBrowser.isActive())
            return false;
        var chord = toKeyChord(key);
        if (chord == null)
            return false;
        var binding = extensionKeybindings.get(chord);
        if (binding == null || binding.handler() == null)
            return false;
        try {
            binding.handler().run();
            redrawPrompt();
        } catch (Exception e) {
            dispatchException(e);
            setStatusOverride("Keybinding error: " + ErrorUtil.message(e));
            redrawPrompt();
        }
        return true;
    }

    static boolean isReservedKey(Key key) {
        return key == Key.Special.ESC || key == Key.Special.DOUBLE_ESC || key == Key.Special.CTRL_C
                                        || key == Key.Special.ENTER || key == Key.Special.NEWLINE
                                        || key == Key.Special.BACKSPACE || key == Key.Special.TAB
                                        || key == Key.Special.PASTE_START || key == Key.Special.CTRL_X;
    }

    static KeyChord toKeyChord(Key key) {
        if (!(key instanceof Key.Special special))
            return null;
        return switch (special) {
            case ENTER -> new KeyChord(KeyChord.Key.ENTER);
            case BACKSPACE -> new KeyChord(KeyChord.Key.BACKSPACE);
            case DELETE -> new KeyChord(KeyChord.Key.DELETE);
            case TAB -> new KeyChord(KeyChord.Key.TAB);
            case ESC -> new KeyChord(KeyChord.Key.ESC);
            case UP -> new KeyChord(KeyChord.Key.UP);
            case DOWN -> new KeyChord(KeyChord.Key.DOWN);
            case LEFT -> new KeyChord(KeyChord.Key.LEFT);
            case RIGHT -> new KeyChord(KeyChord.Key.RIGHT);
            case PG_UP -> new KeyChord(KeyChord.Key.PG_UP);
            case PG_DOWN -> new KeyChord(KeyChord.Key.PG_DOWN);
            case HOME -> new KeyChord(KeyChord.Key.HOME);
            case END -> new KeyChord(KeyChord.Key.END);
            case CTRL_A -> new KeyChord(KeyChord.Key.A, KeyChord.Modifier.CTRL);
            case CTRL_C -> new KeyChord(KeyChord.Key.C, KeyChord.Modifier.CTRL);
            case CTRL_D -> new KeyChord(KeyChord.Key.D, KeyChord.Modifier.CTRL);
            case CTRL_E -> new KeyChord(KeyChord.Key.E, KeyChord.Modifier.CTRL);
            case CTRL_G -> new KeyChord(KeyChord.Key.G, KeyChord.Modifier.CTRL);
            case CTRL_K -> new KeyChord(KeyChord.Key.K, KeyChord.Modifier.CTRL);
            case CTRL_L -> new KeyChord(KeyChord.Key.L, KeyChord.Modifier.CTRL);
            case CTRL_P -> new KeyChord(KeyChord.Key.P, KeyChord.Modifier.CTRL);
            case CTRL_U -> new KeyChord(KeyChord.Key.U, KeyChord.Modifier.CTRL);
            case CTRL_W -> new KeyChord(KeyChord.Key.W, KeyChord.Modifier.CTRL);
            case CTRL_SHIFT_P -> new KeyChord(KeyChord.Key.P, KeyChord.Modifier.CTRL, KeyChord.Modifier.SHIFT);
            case CTRL_LEFT -> new KeyChord(KeyChord.Key.LEFT, KeyChord.Modifier.CTRL);
            case CTRL_RIGHT -> new KeyChord(KeyChord.Key.RIGHT, KeyChord.Modifier.CTRL);
            case SHIFT_TAB -> new KeyChord(KeyChord.Key.TAB, KeyChord.Modifier.SHIFT);
            default -> null;
        };
    }

    /** Cancels an in-flight OAuth login (interrupts its poll loop); returns true if one was active. */
    private boolean cancelAuthTaskIfActive() {
        var task = authTask;
        if (task == null || task.isDone())
            return false;
        task.cancel(true); // interrupt → the login poll's sleep/HTTP send throws; the handler reports "Login cancelled"
        return true;
    }

    private void handleEsc(boolean forceInterrupt) {
        if (cancelAuthTaskIfActive())
            return;
        if (dialogs.cancelQuestionIfIdle(activity.isBusy()))
            return;
        if (!activity.isBusy())
            return;
        if (forceInterrupt || interruptGate.tryFire()) {
            interruptGate.disarm();
            setStatusOverride("Interrupted");
            session.abort();
            streamRenderer.flush();
            streamRenderer.reset();
            shellCommands.interruptProcess();
        } else {
            setTransientStatus("Press Esc again to interrupt", 2500);
        }
        redrawPrompt();
    }

    private void handleCtrlC() {
        if (cancelAuthTaskIfActive())
            return;
        if (exitGate.tryFire()) {
            running.set(false);
            close();
            return;
        }
        promptEditor.setText("");
        setTransientStatus("Press Ctrl+C again to exit", 2500);
        redrawPrompt();
    }

    // Reentrant: nested suspend/resume pairs (e.g. transcendence brackets its whole flow, then suspends again around a
    // subprocess launch) only toggle the terminal on the outermost transition.
    private volatile int suspendDepth = 0;

    public void suspendTui() {
        if (suspendDepth++ > 0)
            return;
        terminal.writer().print("\033[?25h\033[0m\r\n");
        terminal.writer().flush();
        if (originalAttributes != null)
            terminal.setAttributes(originalAttributes);
    }

    public void resumeTui() {
        if (suspendDepth == 0 || --suspendDepth > 0)
            return;
        try {
            originalAttributes = terminal.enterRawMode();
        } catch (Exception ignored) {}
        redrawPrompt();
    }

    // UiContext terminal operations (this TUI is the UiContext for terminal frontends).
    @Override
    public void suspend() {
        suspendTui();
    }

    @Override
    public void resume() {
        resumeTui();
    }

    @Override
    public void appendOutput(List<String> lines) {
        appendPlain(lines);
    }

    private void openPromptInEditor() {
        if (activity.isBusy()) {
            setTransientStatus("Editor unavailable while busy", 2500);
            redrawPrompt();
            return;
        }
        Path file = null;
        try {
            file = Files.createTempFile("pijota-prompt-", ".md");
            Files.writeString(file, promptEditor.text(), StandardCharsets.UTF_8);
            suspendTui();

            String editor = System.getenv().getOrDefault("EDITOR", "vi");
            String shell = settings != null ? settings.effectiveShellPath() : Platform.defaultShell();
            int code = ProcessRunner.command(shell, "-lc", editor + " " + Util.shellQuote(file.toString()))
                                            .runInteractive();

            resumeTui();
            if (code == 0) {
                promptEditor.setText(Files.readString(file, StandardCharsets.UTF_8).stripTrailing());
            } else {
                setTransientStatus("Editor exited with code " + code, 3500);
            }
        } catch (Exception e) {
            resumeTui();
            setStatusOverride("Editor error: " + e.getMessage());
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {}
            }
            redrawPrompt();
        }
    }

    private void handleSubmit(String text) {
        String submitted = Util.stripSystemReminders(text).strip();
        if (submitted.isBlank())
            return;
        submitted = fileRefs.expand(submitted);
        String answer = submitted;
        if (dialogs.submitQuestionAnswer(answer, () -> appendPlain(promptEcho(answer))))
            return;
        String payload = submitted;
        session.stripSystemRemindersFromContext();
        if (payload.startsWith("/")) {
            commandController.handle(payload.strip());
            return;
        }
        if (payload.startsWith("!!")) {
            shellCommands.run(payload.substring(2).strip(), false);
            return;
        }
        if (payload.startsWith("!")) {
            shellCommands.run(payload.substring(1).strip(), true);
            return;
        }
        appendPlain(promptEcho(submitted));
        if (activity.isBusy() && session.isBusy()) {
            session.followUp(payload);
            int queued = followUps.add(payload);
            appendPlain(List.of(theme.styled("statusText", "queued follow-up (" + queued + ")")));
            redrawPrompt();
            return;
        }
        followUps.reset();
        exitGate.disarm();
        long gen = beginBusy("waiting for model");
        redrawPrompt();
        agentExecutor.submit(() -> {
            try {
                streamRenderer.reset();
                session.prompt(payload).join();
            } catch (CompletionException e) {
                dispatchException(e.getCause() != null ? e.getCause() : e);
                setStatusOverride("Error: " + ErrorUtil.message(e.getCause()));
            } catch (Throwable t) {
                dispatchException(t);
            } finally {
                showSessionWarning();
                endBusy(gen);
                redrawPrompt();
            }
        });
    }

    private long beginBusy(String phase) {
        long token = activity.begin(phase);
        interruptGate.disarm();
        clearStatusOverride();
        return token;
    }

    private void endBusy(long generation) {
        if (!activity.end(generation))
            return;
        interruptGate.disarm();
        clearStatusOverride();
    }

    boolean runExtensionCommand(SlashCommand extCmd, String input, String cmd) {
        if (extCmd == null || extCmd.handler() == null)
            return false;
        extCmd.handler().accept(TuiCommandController.commandArgs(input, cmd));
        promptEditor.setText("");
        redrawPrompt();
        return true;
    }

    private void handleResize() {
        redrawPrompt(); // re-sizes the viewer viewport via fitViewerViewport
    }

    private void cycleThinkingLevel() {
        thinkingLevel = thinkingLevel.next();
        session.setThinkingLevel(thinkingLevel);
        settings.setThinkingLevel(thinkingLevel.name());
        appendPlain(List.of(theme.styled("statusText", "thinking " + thinkingLevel.label())));
    }

    void showSkills() {
        skillCommands.showSkills();
    }

    void handleSkillCommand(String input) {
        skillCommands.handleSkillCommand(input);
    }

    private void showSessionWarning() {
        session.consumeSessionWarning().ifPresent(warning -> appendPlain(List.of(theme.styled("toolError", warning))));
    }

    void showSettingsSelector() {
        settingsCommands.showSettingsSelector();
    }

    void showLoginSelector() {
        loginCommands.showLoginSelector();
    }

    void showLogoutSelector() {
        loginCommands.showLogoutSelector();
    }

    void showThemeSelector() {
        themeCommands.showThemeSelector();
    }

    private void showLogSelector() {
        var cmd = extensionCommand("/logs");
        runExtensionCommand(cmd, "/logs", "/logs");
    }

    private CompletableFuture<String> askQuestions(List<SlashCommandTool.Question> questions) {
        return dialogs.askQuestions(questions);
    }

    void showModelSelector() {
        modelCommands.showModelSelector();
    }

    void refreshModels() {
        modelCommands.refreshModels();
    }

    void showModelSelector(List<ModelInfo> models) {
        modelCommands.showModelSelector(models);
    }

    void showSessionPicker(String query) {
        if (activity.isBusy()) {
            appendPlain(List.of(theme.styled("toolError", "/sessions is unavailable while a turn is running")));
            return;
        }
        var sessions = session.resumableSessions();
        if (sessions.isEmpty()) {
            appendPlain(List.of(theme.styled("toolError", "no sessions found")));
            return;
        }
        var items = SessionItem.fromPaths(sessions);
        selectors.show(TuiSelectors.Slot.SESSION, items, query);
        promptEditor.setText(query == null ? "" : query);
        redrawPrompt();
    }

    private void showSessionPicker() {
        showSessionPicker("");
    }

    private void confirmSessionSelection() {
        var chosen = selectors.<SessionItem>selected(TuiSelectors.Slot.SESSION).orElse(null);
        if (chosen == null)
            return;
        selectors.deactivate(TuiSelectors.Slot.SESSION);
        promptEditor.setText("");
        try {
            session.load(chosen.path());
            restoreRuntimeStateFromSession();
            refreshSessionName();
            appendPlain(List.of(theme.styled("statusText", "loaded session " + chosen.name())));
        } catch (IOException e) {
            appendPlain(List.of(theme.styled("toolError", "failed to load session: " + e.getMessage())));
        }
        redrawPrompt();
    }

    private void cycleModel() {
        modelCommands.cycleModel(1);
    }

    private void showCommandPalette() {
        commandPalette.showCommandPalette();
    }

    void showHelpOverlay() {
        // Render help in the scrollable log viewer (one modal viewer for /help and /logs) so the full command list
        // and shortcuts scroll and fit the terminal, rather than being capped.
        logViewer.show("help", HelpOverlay.content(commands.helpLines(), extensionKeybindings.values()));
        redrawPrompt();
    }

    /** Switch via a model alias (e.g. {@code /model alias/fast}), resolved from settings {@code "aliases"}. */
    void switchToAlias(String spec) {
        String name = ModelRegistry.parseProviderModel(spec).model();
        if (ModelRegistry.CURRENT_ALIAS.equalsIgnoreCase(name)) {
            // alias/current is the dynamic, read-only alias for the live model — switching to it is a no-op.
            appendPlain(List.of(theme.styled("statusText", "alias/current = " + provider + "/" + model)));
            return;
        }
        var target = ModelRegistry.resolveAlias(name, settings);
        if (target == null) {
            appendPlain(List.of(theme.styled("toolError", "unknown alias \"" + name
                                            + "\" — define it under \"aliases\" in settings.json")));
            return;
        }
        long ctx = ModelRegistry.resolveContextWindow(target.provider(), target.model());
        switchToModel(new ModelInfo(target.model(), target.provider(), target.provider() + "/" + target.model(), ctx));
    }

    private void switchToModel(ModelInfo chosen) {
        String key = settings != null ? settings.resolveApiKey(chosen.provider()) : null;
        if (key == null || (key.isBlank() && SettingsManager.requiresApiKey(chosen.provider()))) {
            appendPlain(List.of(theme.styled("toolError", "no API key for " + chosen.provider())));
            return;
        }
        applyModel(chosen.provider(), chosen.id(), key, chosen.contextWindow(), true);
        appendPlain(List.of(theme.styled("statusText", provider + "/" + chosen.id() + "  "
                                        + (int) (chosen.contextWindow() / 1024) + "K")));
    }

    private void applyModel(String newProvider, String newModel, String key, long newContextWindow, boolean persist) {
        provider = newProvider;
        model = newModel;
        contextWindow = newContextWindow > 0 ? newContextWindow : resolveContextWindow(newProvider, newModel);
        if (persist)
            session.switchModel(newProvider, newModel, key, contextWindow);
        else
            session.restoreModel(newProvider, newModel, key, contextWindow);
        updateWhoamiTool();
    }

    private void restoreModelFromSession() {
        if (settings == null)
            return;
        try {
            var selection = session.latestModelSelection();
            if (selection == null)
                return;
            var resolved = ModelRegistry.resolve(selection, settings);
            if (resolved == null) {
                appendPlain(List.of(theme.styled("toolError", "session model unavailable: missing API key for "
                                                + selection.provider())));
                return;
            }
            applyModel(resolved.provider(), resolved.model(), resolved.apiKey(), resolved.contextWindow(), false);
            appendPlain(List.of(theme.styled("statusText", "restored model " + provider + "/" + model)));
        } catch (IOException e) {
            appendPlain(List.of(theme.styled("toolError", "model restore failed: " + e.getMessage())));
        }
    }

    private void restoreThinkingLevelFromSession() {
        try {
            var level = session.latestThinkingLevel();
            if (level == null)
                return;
            thinkingLevel = level;
            session.restoreThinkingLevel(level);
        } catch (IOException e) {
            appendPlain(List.of(theme.styled("toolError", "thinking level restore failed: " + e.getMessage())));
        }
    }

    public void syncThinkingLevel(ThinkingLevel level) {
        this.thinkingLevel = level != null ? level : ThinkingLevel.OFF;
    }

    private void restoreRuntimeStateFromSession() {
        restoreModelFromSession();
        restoreThinkingLevelFromSession();
        session.refreshCacheMetadata();
        try {
            session.checkCacheTarget();
        } catch (IOException e) {
            appendPlain(List.of(theme.styled("toolError", "cache check failed: " + e.getMessage())));
        }
        showSessionWarning();
    }

    private void updateWhoamiTool() {
        var tool = session.tool("whoami").orElse(null);
        if (tool instanceof WhoamiTool wt) {
            wt.setModelInfo(provider, model);
        }
    }

    void triggerCompaction(String instructions) {
        if (activity.isBusy()) {
            appendPlain(List.of(theme.styled("toolError", "/compact unavailable while model is responding")));
            return;
        }
        appendPlain(List.of(theme.styled("toolLabel",
                                        "/compact" + (instructions.isBlank() ? "" : ": " + instructions))));
        long gen = beginBusy("compacting");
        streamRenderer.reset();
        streamRenderer.clearCompactionStream();
        redrawPrompt();
        agentExecutor.submit(() -> {
            try {
                if (!session.compact(instructions)) {
                    appendPlain(List.of(theme.styled("statusText", "nothing to compact yet")));
                }
            } catch (Exception e) {
                appendPlain(List.of(theme.styled("toolError", "compact failed: " + e.getMessage())));
            } finally {
                streamRenderer.flush();
                endBusy(gen);
                redrawPrompt();
            }
        });
    }

    void copyLastResponse() {
        sessionCommands.copyLastResponse();
    }

    static String padRight(String s, int width) {
        int w = AnsiWidth.visibleWidth(s);
        return s + " ".repeat(Math.max(0, width - w));
    }

    void showUsage() {
        sessionCommands.showUsage();
    }

    void showContext() {
        sessionCommands.showContext();
    }

    void showSessionInfo() {
        sessionCommands.showSessionInfo();
    }

    void showWhoami() {
        var info = ModelRegistry.KNOWN_MODELS.stream().filter(m -> m.id().equalsIgnoreCase(model)).findFirst()
                                        .orElse(null);
        var lines = new ArrayList<String>();
        lines.add(theme.styled("toolLabel", "model"));
        lines.add(theme.styled("statusText", "id       " + model));
        lines.add(theme.styled("statusText", "provider " + provider));
        if (info != null) {
            lines.add(theme.styled("statusText", "name     " + info.displayName()));
            lines.add(theme.styled("statusText", "context  " + fmtCount((int) info.contextWindow()) + " tokens"));
        }
        appendPlain(lines);
    }

    private void refreshSessionName() {
        try {
            sessionName = session.sessionName();
        } catch (IOException ignored) {
            sessionName = null;
        }
    }

    void exportSession(String input) {
        sessionCommands.exportSession();
    }

    void saveSession(String input) {
        sessionCommands.saveSession(input);
    }

    void loadSession(String input) {
        if (activity.isBusy()) {
            appendPlain(List.of(theme.styled("toolError", "/load is unavailable while a turn is running")));
            return;
        }
        String path = input.length() > 5 ? input.substring(5).trim() : "";
        if (path.isBlank()) {
            showSessionPicker("");
            return;
        }
        try {
            var file = session.resolveSession(path);
            if (!session.messages().isEmpty())
                appendPlain(List.of(theme.styled("statusWarn", "loading session — current messages will be replaced")));
            int count = session.load(file);
            restoreRuntimeStateFromSession();
            refreshSessionName();
            if (count == 0) {
                appendPlain(List.of(theme.styled("toolError", "no messages found in session file")));
                return;
            }
            appendPlain(List.of(theme.styled("statusText",
                                            "loaded " + count + " messages from " + file.getFileName())));
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "load failed: " + e.getMessage())));
        }
    }

    void listSessions() {
        sessionCommands.listSessions();
    }

    void deleteSession(String input) {
        sessionCommands.deleteSession(input);
    }

    void startNewSession() {
        sessionCommands.startNewSession();
    }

    void showTree() {
        if (session.sessionFile() == null) {
            appendPlain(List.of(theme.styled("toolError", "no active session")));
            return;
        }
        try {
            appendPlain(new TreeRenderer(theme).render(session.treeEntries(), session.branchEntries(),
                                            session.leafId()));
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "tree: " + e.getMessage())));
        }
    }

    void forkSession(String input) {
        if (activity.isBusy()) {
            appendPlain(List.of(theme.styled("toolError", "/fork is unavailable while a turn is running")));
            return;
        }
        if (session.sessionFile() == null) {
            appendPlain(List.of(theme.styled("toolError", "no active session")));
            return;
        }
        String arg = input.length() > 5 ? input.substring(5).trim() : "";
        if (arg.isBlank()) {
            showForkSelector();
            return;
        }
        try {
            int idx = Integer.parseInt(arg) - 1;
            var users = session.userMessages();
            if (idx < 0 || idx >= users.size()) {
                appendPlain(List.of(theme.styled("toolError", "invalid message number")));
                return;
            }
            forkFromMessage(users.get(idx), "forked from message " + (idx + 1));
        } catch (NumberFormatException e) {
            appendPlain(List.of(theme.styled("toolError", "expected a number: /fork <n>")));
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "fork failed: " + e.getMessage())));
        }
    }

    private void showForkSelector() {
        var users = session.userMessages();
        if (users.isEmpty()) {
            appendPlain(List.of(theme.styled("statusText", "no user messages to fork from")));
            return;
        }
        var items = new ArrayList<ForkMessageItem>();
        for (int i = 0; i < users.size(); i++) {
            items.add(new ForkMessageItem(i + 1, users.get(i)));
        }
        selectors.show(TuiSelectors.Slot.FORK, items);
        selectors.<ForkMessageItem>selectFirst(TuiSelectors.Slot.FORK, item -> item.number() == users.size());
        setStatusOverride("Select message to fork from; type to filter, Enter forks, Esc cancels");
        redrawPrompt();
    }

    private void confirmForkSelection() {
        var chosen = selectors.<ForkMessageItem>selected(TuiSelectors.Slot.FORK).orElse(null);
        if (chosen == null)
            return;
        selectors.deactivate(TuiSelectors.Slot.FORK);
        clearStatusOverride();
        forkFromMessage(chosen.message(), "forked from message " + chosen.number());
    }

    private void forkFromMessage(Message.User user, String status) {
        try {
            session.forkFrom(user.id().toString());
            restoreRuntimeStateFromSession();
            refreshSessionName();
            appendPlain(List.of(theme.styled("statusText", status)));
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "fork failed: " + e.getMessage())));
        }
    }

    void cloneSession() {
        sessionCommands.cloneSession();
        restoreRuntimeStateFromSession();
        refreshSessionName();
    }

    void undoLastTurn() {
        if (activity.isBusy()) {
            appendPlain(List.of(theme.styled("toolError", "/undo is unavailable while a turn is running")));
            return;
        }
        var previous = session.undoLastUserTurn();
        if (previous.isEmpty()) {
            appendPlain(List.of(theme.styled("statusText", "nothing to undo")));
            return;
        }
        promptEditor.setText(previous.get());
        appendPlain(List.of(theme.styled("statusText", "undid last turn; edit and press Enter to resend")));
        redrawPrompt();
    }

    private void onEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.TurnStart _ -> {
                followUps.clearPending();
                streamRenderer.reset();
                streamingContent = false;
                activity.setPhase("waiting for model");
                redrawPrompt();
            }
            case AgentEvent.AssistantMessage(Message.Assistant msg, var usage) -> {
                if (usage == null)
                    activity.setPhase(null);
                streamingContent = usage == null; // reserve the live-tail row for the whole streaming turn
                String content = msg.content() == null ? "" : msg.content();
                boolean committed = false;
                if (showThinking() && msg.thinking() != null && !msg.thinking().isBlank())
                    committed = streamRenderer.streamThinking(msg.thinking());
                // Assistant messages always stream. Message renderers apply to custom messages (handled by the
                // CustomMessage case below), and never to assistant content — so they must not disable streaming.
                committed = streamRenderer.streamContent(content) || committed;
                if (usage != null) {
                    streamRenderer.closeThinking();
                    streamRenderer.flush();
                    redrawPrompt(); // final frame: clears the now-committed tail, unthrottled
                } else if (committed) {
                    // A line just committed: a full redraw keeps the committed line and the region (with the refreshed
                    // tail) in one consistent frame — otherwise the stale tail makes the prompt jitter up then down.
                    redrawPrompt();
                } else {
                    redrawStreamTail(); // live preview of the in-progress line, ~30fps
                }
            }
            case AgentEvent.ToolStart(var name, var toolCallId, var args) -> {
                streamingContent = false;
                streamRenderer.closeThinking();
                streamRenderer.flush();
                if (toolCallId != null)
                    toolsInProgress.put(toolCallId, new ToolInProgress(args, System.currentTimeMillis()));
                pendingEdit.onToolStart(name, args);
                activity.setPhase("tool " + name);
                if (pendingEdit.hasPath() && pendingEdit.oldText() != null && "edit".equals(name)
                                                && pendingEdit.newText() != null
                                                && !pendingEdit.oldText().equals(pendingEdit.newText())) {
                    appendPlain(new DiffRenderer(theme).render("edit preview", pendingEdit.filePath(),
                                                    pendingEdit.oldText(), pendingEdit.newText(), terminal.getWidth()));
                    pendingEdit.setPreviewShown(true);
                }
                redrawPrompt();
            }
            case AgentEvent.ToolResult(Message.ToolResult msg) -> {
                streamRenderer.flush();
                pendingEdit.onToolResult(isEditWrite(msg), msg.isError());
                ToolInProgress started = msg.toolCallId() != null ? toolsInProgress.remove(msg.toolCallId()) : null;
                Supplier<String> args = started != null ? started.args() : null;
                long elapsedMs = started != null ? System.currentTimeMillis() - started.startedAtMs() : 0;
                Tool.ToolResultView rendered = renderToolResultView(msg);
                if (isEditWrite(msg) && pendingEdit.hasEditResult() && !pendingEdit.previewShown()) {
                    appendPlain(new DiffRenderer(theme).render(msg.toolName(), pendingEdit.filePath(),
                                                    pendingEdit.oldText(), pendingEdit.newText(), terminal.getWidth()));
                }
                String glyph = session.tool(msg.toolName()).map(Tool::glyph).orElse("●");
                appendPlain(ToolResultLines.compose(msg, rendered, args, theme, rawCwd,
                                                settings != null && settings.shortenPathsEnabled(), elapsedMs, glyph));
                pendingEdit.clear();
            }
            case AgentEvent.Compaction c -> {
                int remaining = c.compactedMessages().size();
                int removed = c.oldCount() - remaining;
                int estTokens = c.estimatedTokens();
                String reason = c.reason() != null && !c.reason().isBlank() ? " · " + c.reason() : "";
                if (!streamRenderer.compactionStreamRendered() && c.summary() != null && !c.summary().isBlank()) {
                    streamRenderer.renderCompactionSummary(c.summary());
                }
                appendPlain(List.of(theme.styled("statusText", "[compacted] " + c.oldCount() + " → " + remaining
                                                + " msgs" + " · ~" + fmtCount(estTokens) + " tokens" + reason)));
                streamRenderer.clearCompactionStream();
            }
            case AgentEvent.CompactionStream stream -> streamRenderer.renderCompactionStream(stream.content());
            case AgentEvent.CompactionStreamEnd _ -> {
                streamRenderer.flush();
                streamRenderer.reset();
            }
            case AgentEvent.CustomMessage(Message.Custom custom) -> {
                streamRenderer.flush();
                if (session.extensionRegistry().hasMessageRenderers()) {
                    var rendered = session.extensionRegistry().renderMessage(custom);
                    if (rendered.isPresent()) {
                        appendPlain(List.of(theme.styled("customMessageLabel", custom.customType())));
                        appendPlain(rendered.get());
                    } else {
                        appendPlain(List.of(theme.styled("customMessageLabel", custom.customType() + ": ")
                                                        + custom.content()));
                    }
                } else {
                    appendPlain(List.of(theme.styled("customMessageLabel", custom.customType() + ": ")
                                                    + theme.styled("customMessageText", custom.content())));
                }
                redrawPrompt();
            }
            case AgentEvent.Error(Throwable t) ->
                appendPlain(List.of(theme.styled("toolError", "error: " + ErrorUtil.message(t))));
            default -> {}
        }
    }

    private Tool.ToolResultView renderToolResultView(Message.ToolResult msg) {
        var tool = session.tool(msg.toolName());
        // A tool gets its own rendering iff it advertises a custom view; no tool names are hardcoded here.
        if (tool.isEmpty() || !tool.get().hasResultView())
            return null;
        var args = pendingEdit.toolArgs() != null
                                        ? Util.parseJsonArgs(pendingEdit.toolArgs().get())
                                        : Map.<String, Object>of();
        return tool.get().renderResult(new Tool.Args(args), new Tool.ToolResult(msg.content(), msg.isError(),
                                        msg.details() == null ? Map.of() : msg.details()), rawCwd);
    }

    private void dispatchException(Throwable t) {
        appendPlain(List.of(theme.styled("toolError", "Exception: " + ErrorUtil.message(t))));
        if (session != null && session.extensionRegistry() != null) {
            session.extensionRegistry().dispatchException(t);
        }
    }

    public void appendPlain(List<String> lines) {
        terminalUi.appendLines(lines.stream().map(StyledLine::of).toList());
    }

    private List<String> promptEcho(String text) {
        var out = new ArrayList<String>();
        String[] split = text.split("\\R", -1);
        for (int i = 0; i < split.length; i++) {
            out.add(theme.styled("promptEcho", i == 0 ? "▸ " : "│ ") + split[i]);
        }
        return out;
    }

    private void setStatusOverride(String status) {
        statusOverride.set(status);
    }

    private void setTransientStatus(String status, long durationMs) {
        statusOverride.setTransient(status, durationMs);
    }

    private void clearStatusOverride() {
        statusOverride.clear();
    }

    static boolean isEditWrite(Message.ToolResult msg) {
        return "edit".equals(msg.toolName()) || "write".equals(msg.toolName());
    }

    private boolean showThinking() {
        return thinkingLevel != ThinkingLevel.OFF && (settings == null || !settings.config().hideThinking());
    }

    private void tickWorkingAnimation() {
        // scheduleAtFixedRate silently cancels the task forever if it ever throws — a single transient redraw error
        // would then freeze the working spinner for the rest of the session (the prompt shows a frozen "●●●" while the
        // turn keeps running). Swallow everything so the next tick still fires.
        try {
            // While suspended (editor/shell handed the terminal to a child), don't advance or repaint — the
            // redrawPrompt guard is the backstop; this stops the tick doing work at the source.
            if (!running.get() || SelfRespawn.isRespawnInProgress() || suspendDepth > 0)
                return;
            boolean redraw = false;
            if (logViewer.active() && logViewer.refreshIfDue()) // repaint only when a live log tailed new content
                redraw = true;
            if (activity.isBusy()) {
                animation.advance();
                redraw = true;
            }
            if (statusOverride.expireIfDue())
                redraw = true;
            if (redraw)
                redrawPrompt();
        } catch (Throwable t) {
            log.debug("working-animation tick failed (will retry next tick)", t);
        }
    }

    /**
     * Routes a key to a modal component (log viewer, select picker) that consumes input while active. Returns true if
     * the key was consumed, redrawing afterwards. Modals don't touch {@code busy} — that reflects the agent turn, so
     * the working spinner shows only while the model is actually running, not while a viewer/picker is open.
     */
    private boolean routeModalKey(java.util.function.BooleanSupplier active,
                                    java.util.function.Predicate<Key> handleKey, Key key) {
        if (!active.getAsBoolean() || !handleKey.test(key))
            return false;
        redrawPrompt();
        return true;
    }

    private void redrawPrompt() {
        // While suspended (e.g. OAuth /login printing the device URL via System.out), the terminal is handed back to
        // raw stdout; repainting the prompt/status bar here would interleave with that output (the model/status bar
        // bled into the printed login URL). Skip all repaints until resumed.
        if (suspendDepth > 0 || SelfRespawn.isRespawnInProgress())
            return;
        var snapshot = promptPresenter.decorate(promptEditor.snapshot());
        if (logViewer.active())
            logViewer.setViewport(fitViewerViewport(snapshot));
        terminalUi.redrawPrompt(snapshot);
    }

    // Coalesces the live streaming-tail repaint to ~30fps. The terminal writer thread already batches enqueued frames;
    // this just avoids rebuilding a snapshot per token on the event thread. The final, line-committing frame goes
    // through the unthrottled redrawPrompt(), and the 125ms working-animation tick is a backstop, so nothing is lost.
    private static final long STREAM_REPAINT_INTERVAL_MS = 33;
    private long lastStreamRepaintAt;

    private void redrawStreamTail() {
        if (suspendDepth > 0)
            return;
        long now = System.currentTimeMillis();
        if (now - lastStreamRepaintAt < STREAM_REPAINT_INTERVAL_MS)
            return;
        lastStreamRepaintAt = now;
        String tail = streamRenderer.liveTail().orElse(null);
        // Blank tail (between lines) needs no repaint: the just-committed line's append frame already refreshed the
        // region. Otherwise repaint just the tail row in place — no whole-region erase, so streaming never strobes.
        if (tail != null && !tail.isBlank())
            terminalUi.updateStreamTail(tail);
    }

    /**
     * Body rows the log viewer may occupy so the whole prompt frame still fits on screen. The append-only redraw erases
     * the previous frame by moving the cursor up over it; a frame taller than the terminal scrolls its top off-screen
     * out of reach, leaving stale separators/prompts piled up. So budget the viewer against the measured height of
     * every other region, recomputed each redraw to track the status/extension area as it grows or shrinks.
     */
    private int fitViewerViewport(PromptSnapshot snapshot) {
        int width = Math.max(20, terminal.getWidth());
        int others = promptLayout.measuredHeightExcluding(LogViewerRegion.class, width, snapshot);
        // The viewer region wraps the body in a header + footer (2 rows); keep one spare line so a region whose
        // height is under-estimated by a row can never push the frame past the bottom of the screen.
        return Math.max(3, terminal.getHeight() - others - 3);
    }

    static String gitBranch(String cwd) {
        try {
            String out = ProcessRunner.command("git", "branch", "--show-current").workDir(Path.of(cwd))
                                            .timeout(5, TimeUnit.SECONDS).runCapture().strip();
            if (!out.isEmpty())
                return " (" + out + ")";
        } catch (Exception ignored) {}
        return "";
    }

    private static long resolveContextWindow(String provider, String model) {
        return ai.pijota.llm.ModelRegistry.resolveContextWindow(provider, model);
    }

    static int pendingHeight(int width, PromptSnapshot ctx) {
        if (ctx.pendingMessages().isEmpty())
            return 0;
        int h = 0;
        for (String msg : ctx.pendingMessages()) {
            String line = "queued  " + msg;
            int vw = AnsiWidth.visibleWidth(line);
            h += (vw <= 0) ? 1 : (vw + width - 1) / width;
        }
        return h;
    }

    private final class PendingRegion implements PromptRegion.Custom {
        @Override
        public int height(int width, PromptSnapshot ctx) {
            return pendingHeight(width, ctx);
        }

        @Override
        public CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx) {
            if (ctx.pendingMessages().isEmpty())
                return NO_CURSOR;
            boolean first = true;
            for (int i = 0; i < ctx.pendingMessages().size(); i++) {
                String wrapped = AnsiWidth.truncateVisual("queued  " + ctx.pendingMessages().get(i), width);
                if (!first)
                    out.print("\r\n");
                out.print('\r');
                out.print("\033[K");
                out.print(theme.styled("statusWarn", wrapped));
                first = false;
            }
            return NO_CURSOR;
        }
    }
}
