package org.pijota.tui;

import org.pijota.agent.AgentEvent;
import org.pijota.agent.PijotaSession;
import org.pijota.agent.SessionDescriptor;
import org.pijota.config.PathDisplay;
import org.pijota.config.PromptGlyphs;
import org.pijota.config.SettingsManager;
import org.pijota.extensions.ExtensionKeybinding;
import org.pijota.extensions.KeyChord;
import org.pijota.extensions.UiContext;
import org.pijota.extensions.jobs.JobRegistry;
import org.pijota.extensions.skills.SkillLoader;
import org.pijota.llm.Modality;
import org.pijota.llm.ModelDisplay;
import org.pijota.llm.ModelInfo;
import org.pijota.llm.ModelRegistry;
import org.pijota.llm.ThinkingLevel;
import org.pijota.messages.Message;
import org.pijota.modes.SlashCommand;
import org.pijota.theme.AnsiTheme;
import org.pijota.tools.SlashCommandTool;
import org.pijota.tools.Tool;
import org.pijota.tools.ToolGlyphs;
import org.pijota.tools.ToolLabels;
import org.pijota.tools.WhoamiTool;
import org.pijota.util.ErrorUtil;
import org.pijota.util.Platform;
import org.pijota.util.ProcessRunner;
import org.pijota.util.SelfRespawn;
import org.pijota.util.Util;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.pijota.util.Util.fmtCount;
import static org.pijota.util.Util.shortenCwd;

public final class AppendOnlyTui implements AutoCloseable, UiContext {
    private static final Logger log = LoggerFactory.getLogger(AppendOnlyTui.class);
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
    private final ExternalEditor externalEditor = new ExternalEditor();
    private final StatusOverride statusOverride = new StatusOverride();
    private final DialogHandler dialogs;
    private final PromptPresenter promptPresenter;
    private final PromptSelector selector = new PromptSelector();
    private final GlobalKeyMap globalKeys;
    private final Map<Key.Special, Key.Special> editorKeyRemaps;
    private final Map<Key.Special, Key.Special> selectorKeyRemaps;

    @Override
    public void setStatus(String msg) {
        setStatusOverride(msg);
    }

    @Override
    public void refresh() {
        redrawPrompt(); // async extension state changed (e.g. an MCP server connected); repaint the chrome
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
    public <T> void select(String title, List<T> items, Function<T, String> render, Consumer<T> onChoice) {
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
    public void viewer(String title, Supplier<List<String>> source) {
        logViewer.showLive(title, source);
        redrawPrompt();
    }

    @Override
    public boolean acceptsPromptDrafts() {
        return true; // the prompt editor is the draft
    }

    @Override
    public void offerSuggestion(String text) {
        if (text == null || text.isBlank())
            return;
        promptEditor.offer(text);
        setTransientStatus("Tab to use", 4000); // an affordance, not a warning: it says what the key does
        redrawPrompt();
    }

    @Override
    public void submitUserMessage(String content) {
        handleSubmit(content);
    }

    public void appendStatus(String msg) {
        appendPlain(List.of(theme.styled("statusText", msg)));
    }

    /** See {@link TuiHost#refuseWhileBusy}: the same refusal, for the commands the TUI owns directly. */
    private boolean refuseWhileBusy(String command) {
        if (!activity.isBusy())
            return false;
        appendPlain(List.of(theme.styled("statusWarn", TuiHost.busyRefusal(command))));
        return true;
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
    private final DoublePressGate backgroundGate = new DoublePressGate(INTERRUPT_WINDOW_MS);
    private volatile JobRegistry jobRegistry; // enables ctrl+b ctrl+b; null until the host wires it
    private ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
    private String sessionName;
    // volatile: assertTerminalModes() snapshots this from the SIGCONT handler's thread, and suspendTui()/close() read
    // it from the input thread. suspendDepth next door is already volatile for the same crossing; this one was not, so
    // the restore could read a stale null and hand the shell-out a raw terminal.
    private volatile Attributes originalAttributes;
    private final Terminal.SignalHandler originalIntHandler;
    private final Terminal.SignalHandler originalWinchHandler;
    private final SlashCommandRegistry commands;
    private final TuiCommandController commandController;
    private final Map<String, SlashCommand> extensionCommands;
    private final Map<KeyChord, ExtensionKeybinding> extensionKeybindings;
    /**
     * Per-tool-call state captured at ToolStart, consumed at ToolResult: args for the header + start time for elapsed.
     * The name and pre-computed target detail also feed the live stream-tail line while the tool runs.
     */
    private record ToolInProgress(String name, String detail, Supplier<String> args, long startedAtMs) {
    }

    private final Map<String, ToolInProgress> toolsInProgress = new ConcurrentHashMap<>();
    // Calls already reported as interrupted. Their results still arrive (a killed process reports exit 143), and
    // rendering those would report one interrupted call twice: once as "interrupted", once as a failed block.
    private final Set<String> interruptedCalls = ConcurrentHashMap.newKeySet();
    private final TuiSelectors selectors;
    private volatile boolean showSessionPickerOnStart;
    private final LogViewer logViewer = new LogViewer();
    private final WorkingAnimation animation = new WorkingAnimation();
    private final MessageBrowser messageBrowser;
    /** The prompt draft a selector displaced when it borrowed the input line; restored if the selector is cancelled. */
    private String promptStash = "";
    private PendingEditManager pendingEdit;
    private final Supplier<List<String>> extensionLinesSupplier;
    private final RemoteControl remoteControl;
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

    /** Wires the shared background-job registry, enabling ctrl+b ctrl+b / {@code /bg} for running commands. */
    public void setJobRegistry(JobRegistry jobs) {
        this.jobRegistry = jobs;
    }
    private static final long INTERRUPT_WINDOW_MS = 1200;
    private static final long EXIT_WINDOW_MS = 800;
    // Short TTL so the context meter visibly tracks the streaming response (the count grows within a single message,
    // which doesn't change the message-count cache key). The estimate is cheap, so refreshing a few times a second is
    // fine.
    private static final long TOKEN_STATUS_CACHE_MS = 150;
    private final ContextTokenMeter tokenMeter;

    /** Production entry point: builds the real system terminal. Tests use the package-private constructor below. */
    public AppendOnlyTui(PijotaSession session, AnsiTheme theme, String provider, String model,
                                    List<SlashCommand> extensionCommands, SettingsManager settings,
                                    List<SkillLoader.Skill> skills, Supplier<List<String>> extensionLinesSupplier,
                                    List<ExtensionKeybinding> extensionKeybindings) throws IOException {
        this(session, theme, provider, model, extensionCommands, settings, skills, extensionLinesSupplier,
                                        extensionKeybindings, systemTerminal(theme));
    }

    /**
     * Builds the real system terminal. Two startup concerns handled here so the injectable test constructor stays pure:
     * (1) {@code graphemeCluster(false)} skips JLine's mode-2027 probe, which otherwise sends a query and blocks ~350
     * ms on the path to the prompt when the terminal doesn't answer; pijota measures widths itself (AnsiWidth,
     * wcwidth-based) and uses JLine only for raw key I/O, so its grapheme setting is irrelevant to rendering. (2) A
     * {@code dumb} (no-color) terminal can't render ANSI, so drop the theme to plain rather than spray escape codes as
     * garbage, mirroring the {@code NO_COLOR} behaviour.
     */
    private static Terminal systemTerminal(AnsiTheme theme) throws IOException {
        Terminal terminal = TerminalBuilder.builder().dumb(false).jna(false).jansi(false).graphemeCluster(false)
                                        .build();
        if (theme.enabled && isNoColorTerminal(terminal.getType()))
            theme.enabled = false;
        return terminal;
    }

    /**
     * A JLine {@code dumb} terminal has no color; {@code dumb-color} is its color-capable variant, so only plain
     * "dumb".
     */
    static boolean isNoColorTerminal(String type) {
        return type != null && type.startsWith("dumb") && !type.contains("color");
    }

    /** Injectable-terminal constructor: lets tests drive the TUI against an in-memory terminal (no tty). */
    AppendOnlyTui(PijotaSession session, AnsiTheme theme, String provider, String model,
                                    List<SlashCommand> extensionCommands, SettingsManager settings,
                                    List<SkillLoader.Skill> skills, Supplier<List<String>> extensionLinesSupplier,
                                    List<ExtensionKeybinding> extensionKeybindings, Terminal terminal)
                                    throws IOException {
        this.commands = new SlashCommandRegistry(extensionCommands);
        this.promptEditor = new PromptEditor(commands);
        var userKeys = UserKeybindings.load();
        this.globalKeys = buildGlobalKeyMap(userKeys);
        this.editorKeyRemaps = buildEditorKeyRemaps(userKeys);
        this.selectorKeyRemaps = buildSelectorKeyRemaps(userKeys);
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
        this.promptPresenter = new PromptPresenter(host, activity, statusOverride, dialogs, animation,
                                        session::queuedFollowUps, tokenMeter, this::statusLinesWithRemote);
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
        this.terminal = terminal;
        // INT is handled by the byte path (InputDecoder case 3 → the input loop's CTRL_C branch), not here: JLine's
        // SignalInterceptingInputStream raises Signal.INT *and* still returns byte 0x03 to the reader, so a handler
        // here would fire handleCtrlC() twice per Ctrl+C — arming exitGate on the first press and exiting on the
        // "second". Register a no-op purely so shutdown has a stable originalIntHandler to restore.
        this.originalIntHandler = this.terminal.handle(Terminal.Signal.INT, ignored -> {});
        this.originalWinchHandler = this.terminal.handle(Terminal.Signal.WINCH, ignored -> handleResize());
        // Confirm callbacks point straight at the owning handlers; session/fork keep TUI-local restore logic.
        this.selectors = new TuiSelectors();
        // One overlay at a time: a selector already owns the screen and filters on this very text, so the completions
        // popup must not open on top of it.
        this.promptEditor.setCompletionsAllowed(() -> !selectors.anyActive());
        selectors.bind(TuiSelectors.Slot.MODEL, modelCommands::confirmModelSelection);
        selectors.bind(TuiSelectors.Slot.SESSION, this::confirmSessionSelection);
        selectors.bind(TuiSelectors.Slot.TREE, this::confirmTreeSelection);
        selectors.bind(TuiSelectors.Slot.THEME, themeCommands::confirmThemeSelection);
        selectors.bind(TuiSelectors.Slot.SETTINGS, settingsCommands::confirmSettingsSelection);
        selectors.bind(TuiSelectors.Slot.LOGIN, loginCommands::confirmProviderSelection);
        selectors.bind(TuiSelectors.Slot.FORK, this::confirmForkSelection);
        selectors.bind(TuiSelectors.Slot.PALETTE, commandPalette::confirmPaletteSelection);
        selectors.bind(TuiSelectors.Slot.REMOTE, this::confirmRemoteSelection);
        var regions = new ArrayList<PromptRegion>();
        var promptGlyphs = settings != null && settings.config().prompt() != null
                                        ? settings.config().prompt()
                                        : PromptGlyphs.DEFAULTS;
        regions.add(new PromptRegion.Editor(promptGlyphs));
        regions.add(new PendingRegion());
        regions.addAll(selectors.regions());
        regions.add(new PromptRegion.Completions());
        regions.add(new PromptSelectorRegion(selector));
        regions.add(new LogViewerRegion(logViewer));
        regions.add(new PromptRegion.StatusBar());
        this.promptLayout = new PromptLayout(regions, promptGlyphs.topDivider());
        this.terminalUi = new TerminalCoordinator(terminal, theme, promptLayout);
        this.streamRenderer = new StreamRenderer(terminalUi::appendLines, theme, thinkingTheme,
                                        () -> terminal.getWidth() - 4, this::showThinking, () -> thinkingLevel.label(),
                                        settings != null
                                                                        ? MarkdownStyle.from(settings.config()
                                                                                                        .markdown())
                                                                        : MarkdownStyle.RICH);
        this.extensionCommands = new HashMap<>();
        for (var cmd : extensionCommands) {
            this.extensionCommands.put("/" + cmd.name(), cmd);
            for (String alias : cmd.aliases()) {
                this.extensionCommands.put("/" + alias, cmd);
            }
        }
        this.extensionKeybindings = new HashMap<>();
        var deliverable = deliverableChords();
        for (var binding : extensionKeybindings) {
            if (binding == null || binding.key() == null)
                continue;
            // A chord no key can produce would sit in /help as a shortcut that does nothing when pressed -- the same
            // defect as a flag --help advertises and nothing reads. Dropped here so /help, which lists this map,
            // cannot advertise it.
            if (!deliverable.contains(binding.key())) {
                log.warn("keybinding {} ({}) is not deliverable by this terminal frontend; ignoring",
                                                binding.key().display(), binding.description());
                continue;
            }
            this.extensionKeybindings.put(binding.key(), binding);
        }
        this.commandController = new TuiCommandController(this, commands);
        this.remoteControl = new RemoteControl(session, settings, extensionLinesSupplier,
                                        session.extensionRegistry()::slashCommands, this);
        updateWhoamiTool();
        session.addEventListener(this::onEvent);
        seedPromptHistoryFromSession(); // resumed/--continue sessions: make prior prompts recall-able with up/down
    }

    /**
     * Populate the prompt's up/down history with the current session's user messages so a loaded/resumed conversation
     * can be explored with the arrow keys. The transcript itself is not reprinted (append-only); only input recall is
     * restored. Replaces any existing entries, so it tracks the active session across /load, /new, /undo and /fork.
     */
    private void seedPromptHistoryFromSession() {
        promptEditor.seedHistory(session.messages().stream().filter(m -> m instanceof Message.User)
                                        .map(m -> ((Message.User) m).content()).filter(c -> c != null && !c.isBlank())
                                        .toList());
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
        public int messageCount() {
            return session.messages().size();
        }

        @Override
        public String liveStreamTail() {
            if (streamingContent)
                return streamRenderer.liveTail().orElse("");
            return runningToolTail();
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
            // Selectors borrow the prompt line for their query, so opening one clears the editor. Stash what was
            // there: cancelling must give the user their draft back, not eat it (Ctrl+P over a half-written message
            // is the common case). A confirmed selection clears again over an empty editor, dropping the stash.
            promptStash = promptEditor.text();
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
        public List<String> shellBlock(String marker, String command, String stdout, int exitCode, long elapsedMs) {
            return ToolResultLines.shellBlock(marker, command, stdout, exitCode, elapsedMs, theme, blockWidth());
        }

        @Override
        public void setRunningShellCommand(String command) {
            runningShell = command == null ? null : new RunningShell(command, System.currentTimeMillis());
        }

        @Override
        public Map<String, String> shellEnvironment() {
            var env = new HashMap<String, String>();
            env.put("PIJOTA_PROVIDER", null);
            env.put("PIJOTA_MODEL", null);
            env.put("PIJOTA_REASONING_LEVEL", null);
            env.put("PIJOTA_SESSION_FILE", null);
            env.put("PIJOTA_SESSION_ID", null);
            env.put("PIJOTA_PROVIDER", provider);
            env.put("PIJOTA_MODEL", model);
            env.put("PIJOTA_REASONING_LEVEL", thinkingLevel.name().toLowerCase(Locale.ROOT));
            if (session.sessionFile() != null) {
                Path file = session.sessionFile();
                env.put("PIJOTA_SESSION_FILE", file.toString());
                String name = file.getFileName().toString();
                env.put("PIJOTA_SESSION_ID", name.endsWith(".jsonl") ? name.substring(0, name.length() - 6) : name);
            }
            return env;
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
        public Optional<ThemeItem> selectedTheme() {
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
                                            settings.compactionEnabled(), settings.pathDisplay(), thinkingLevel,
                                            cfg.hideThinking(), cfg.quietStartup(), animation.style());
        }

        @Override
        public void showSettings(List<SettingsItem> items) {
            selectors.show(TuiSelectors.Slot.SETTINGS, items);
        }

        @Override
        public Optional<SettingsItem> selectedSetting() {
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
        public void cyclePathDisplay() {
            settings.cyclePathDisplay();
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
        public Optional<LoginProviderItem> selectedProvider() {
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
            return commands.all(); // the palette lists every command; "" would hit the slash-prefix guard and return
                                   // none
        }

        @Override
        public List<ModelInfo> availableModels() {
            return ModelRegistry.availableModels(settings);
        }

        @Override
        public Optional<ModelInfo> model(String id) {
            return ModelRegistry.byId(id);
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
        public Optional<CompletionItem> selectedPaletteItem() {
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
            // Ask for unambiguous modifiers before anything reads a key. Terminals without the protocol ignore it.
            assertTerminalModes(); // first call also snapshots the pre-TUI attributes into originalAttributes
            installSigcontHandler();
            appendStartupGreeting(version, cwd);
            if (showSessionPickerOnStart) {
                showSessionPicker();
                showSessionPickerOnStart = false;
            }
            terminalUi.start(promptPresenter.decorate(promptEditor.snapshot()));
            animator.scheduleAtFixedRate(this::tickWorkingAnimation, 125, 125, TimeUnit.MILLISECONDS);
            inputThread = Thread.ofPlatform().name("pijota-tui-input").uncaughtExceptionHandler((t, e) -> {
                // Last resort: never leave the terminal in raw mode if the input
                // thread dies unexpectedly; restore it and unblock shutdown.
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

    /**
     * The wordmark, split the way the web UI splits it: {@code pi} plain, {@code jota} in the accent. The shared root
     * stays quiet and the part that is pijota's carries the colour, which reads as a name rather than as a version
     * string and tells the lineage without a sentence about it.
     */
    private String wordmark() {
        return theme.styled("bannerRoot", "pi") + theme.styled("bannerName", "jota");
    }

    private void appendStartupGreeting(String version, String cwd) {
        if (settings != null && settings.config().quietStartup()) {
            appendPlain(List.of(wordmark() + theme.styled("bannerMeta", " " + version)));
            return;
        }
        var lines = new ArrayList<String>();
        // Three levels, loudest last-but-one: the name carries the accent, the model is the fact worth reading
        // every launch, and the version is trivia. They shared one slot, so the eye had nothing to land on.
        lines.add(wordmark() + theme.styled("bannerMeta", " " + version) + TuiCopy.GROUP
                                        + theme.styled("commandMeta", PromptPresenter.modelLabel(provider, model)));
        lines.add(theme.styled("dim", TuiCopy.START_HINT));

        // recent sessions
        try {
            var items = SessionItem.fromPaths(session.resumableSessions(), 3);
            if (!items.isEmpty()) {
                lines.add("");
                lines.add(theme.styled("dim", "recent sessions"));
                int width = Math.max(20, terminal.getWidth());
                var metas = items.stream().map(AppendOnlyTui::bannerSessionMeta).toList();
                // Metadata in one column so ages and counts can be compared straight down the list; trailing each
                // title, it landed somewhere different on every row. The column sits just past the longest title
                // rather than against the right margin, which would leave a canyon of blank the eye has to cross.
                int metaWidth = metas.stream().mapToInt(AnsiWidth::visibleWidth).max().orElse(0);
                int titleRoom = Math.max(8, width - metaWidth - 4); // 2 leading + one GROUP gap
                // Columns, not characters: a CJK title cut to N characters is 2N columns wide, and the row wrapped.
                var titles = items.stream().map(it -> AnsiWidth.ellipsize(it.description(), titleRoom)).toList();
                int titleColumn = titles.stream().mapToInt(AnsiWidth::visibleWidth).max().orElse(0);
                // The title carries the emphasis and the age gives way, which was the wrong way round: statusText is
                // dimmed and commandMeta is not, so the eye landed on "1h ago" rather than on which session it was.
                for (int i = 0; i < titles.size(); i++)
                    lines.add(theme.styled("commandMeta", TuiCopy.GROUP + padRight(titles.get(i), titleColumn))
                                                    + TuiCopy.GROUP + theme.styled("statusText", metas.get(i)));
            }
        } catch (Exception ignored) {}
        appendPlain(lines);
    }

    /**
     * The banner's one-glance metadata for a session. Built from the item's own fields rather than by cutting up the
     * selector's detail string, which also carried an absolute timestamp and the session id: at three rows the question
     * is "which one was I just in", and a relative age answers it without a second time format competing.
     */
    static String bannerSessionMeta(SessionItem item) {
        var parts = new ArrayList<String>();
        String age = SessionDescriptor.relativeAge(item.mtime());
        if (age != null && !age.isBlank())
            parts.add(age);
        if (item.messageCount() > 0) {
            parts.add(Util.plural(item.messageCount(), "msg"));
            if (item.estTokens() > 0)
                parts.add("~" + SessionDescriptor.formatTokens(item.estTokens()));
        }
        return String.join(" · ", parts);
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
        if (jobRegistry != null)
            jobRegistry.killAll(); // backgrounded ≠ daemonized; backstop for when the jobs extension is disabled
        // Tear down any remote channel on EVERY exit, not just "/remote-control → stop": otherwise a foreground
        // tunnel child (cloudflared / tailscale serve) outlives the TUI, still proxying to a dead socket.
        remoteControl.stop(); // a no-op when nothing is running; the field is final and set in the constructor
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
            // Undo every mode the render loop may have set, including autowrap (?7h): each frame turns it OFF while
            // drawing the prompt and back ON after, so exiting mid-frame can leave the shell with autowrap disabled
            // (garbled line-wrap + readline cursor). End sync (?2026l), bracketed paste (?2004l), autowrap on (?7h),
            // show cursor (?25h), reset colors (0m).
            // Pop the Kitty keyboard mode first: once it is off, a late key release cannot arrive as a CSI-u
            // sequence that the shell would print as literal text.
            terminal.writer().print(KittyKeyboard.DISABLE + "\033[?2026l\033[?2004l\033[?7h\033[?25h\033[0m");
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
                    if (key == Key.Special.CTRL_Z) { // job-control suspend (works from any state)
                        suspendProcess();
                        continue;
                    }
                    if (dialogs.handleConfirm(key))
                        continue;
                    if (dialogs.handlePrompt(key))
                        continue;
                    if (routeModalKey(logViewer::active, logViewer::handleKey, key))
                        continue;
                    // The select picker is modal too: route keys to it before global ESC/etc. so Esc cancels the
                    // picker rather than triggering a generic escape.
                    if (routeModalKey(selector::active, selector::handleKey, key))
                        continue;
                    if (handleExtensionKeybinding(key))
                        continue;
                    // Every overlay is cancellable with Esc, double-Esc, or a single Ctrl+C; it closes instead of
                    // falling through to interrupt/exit. The log viewer and select picker also consume plain Esc via
                    // routeModalKey above; this is the single guarantee for their Ctrl+C and for the selectors, the
                    // completions popup (incl. the Ctrl+R history search), and the message browser.
                    if (key == Key.Special.ESC || key == Key.Special.DOUBLE_ESC || key == Key.Special.CTRL_C) {
                        if (cancelActiveOverlay()) {
                            redrawPrompt();
                            continue;
                        }
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
                    if (key == Key.Special.CTRL_B) {
                        handleCtrlB();
                        continue;
                    }
                    // The session picker's own Ctrl+X (delete) is handled before the global keymap. It no-ops
                    // unless the picker is open, so it's safe here first.
                    if (handleSessionPickerAction(key))
                        continue;
                    // App shortcuts (command palette, model picker, editor, …) are modal-suppressed while an overlay is
                    // open; otherwise one selector-opening chord stacks a second selector on top of the first (e.g.
                    // Ctrl+P over the /sessions picker). Mirrors the same guard on extension keybindings; Esc and the
                    // navigation keys still reach the active overlay through the routing below.
                    if (!anyOverlayActive() && globalKeys.dispatch(key))
                        continue;
                    if (key == Key.Special.ALT_UP) {
                        undoLastTurn();
                        continue;
                    }
                    if (key == Key.Special.PASTE_START) {
                        Key paste = InputDecoder.readPaste(terminal);
                        if (paste instanceof Key.Paste p) {
                            promptEditor.insertText(p.text());
                            redrawPrompt();
                        }
                        continue;
                    }
                    key = remapSpecial(key, selectorKeyRemaps);
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
                    if (key instanceof Key.Char at && at.value() == '@' && !activity.isBusy()
                                                    && FzfFilePicker.available()) {
                        pickFileWithFzf(true); // @ opens fzf file completion; the chosen path is inserted as @"…"
                        continue;
                    }
                    var result = promptEditor.apply(remapSpecial(key, editorKeyRemaps));
                    // Ctrl+R on an empty history opened nothing and said nothing, so an advertised key read as
                    // broken. The editor reports no change in that case, so the redraw below never runs: say it here.
                    if (key == Key.Special.CTRL_R && !promptEditor.hasCompletions()) {
                        setTransientStatus("no history to search yet", 2500);
                        redrawPrompt();
                    }
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
                appendPlain(List.of(theme.styled("toolError", ErrorUtil.stackTrace(e))));
                setTransientStatus("Error handling input. Terminal still active; restart pijota if it persists", 5000);
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

    private GlobalKeyMap buildGlobalKeyMap(UserKeybindings keys) {
        var map = new GlobalKeyMap();
        bind(map, keys.keysFor("app.editor.external", Key.Special.CTRL_G), this::openPromptInEditor);
        bind(map, keys.keysFor("app.thinking.cycle", Key.Special.SHIFT_TAB), this::cycleThinkingLevel);
        bind(map, keys.keysFor("app.model.select", Key.Special.CTRL_L), this::showModelSelector);
        bind(map, keys.keysFor("app.command.palette", Key.Special.CTRL_P), this::showCommandPalette);
        bind(map, keys.keysFor("app.file.pick", Key.Special.CTRL_T), this::pickPathWithFzf);
        bind(map, keys.keysFor("app.tools.expand", Key.Special.CTRL_O), this::toggleStatusExpanded);
        bind(map, keys.keysFor("app.model.cycleBackward", Key.Special.CTRL_SHIFT_P), this::cycleModel);
        return map;
    }

    private static Map<Key.Special, Key.Special> buildEditorKeyRemaps(UserKeybindings keys) {
        return keys.remaps(Map.ofEntries(Map.entry("tui.editor.cursorUp", Key.Special.UP),
                                        Map.entry("tui.editor.cursorDown", Key.Special.DOWN),
                                        Map.entry("tui.editor.cursorLeft", Key.Special.LEFT),
                                        Map.entry("tui.editor.cursorRight", Key.Special.RIGHT),
                                        Map.entry("tui.editor.cursorLineStart", Key.Special.HOME),
                                        Map.entry("tui.editor.cursorLineEnd", Key.Special.END),
                                        Map.entry("tui.editor.cursorWordLeft", Key.Special.CTRL_LEFT),
                                        Map.entry("tui.editor.cursorWordRight", Key.Special.CTRL_RIGHT),
                                        Map.entry("tui.editor.deleteCharBackward", Key.Special.BACKSPACE),
                                        Map.entry("tui.editor.deleteCharForward", Key.Special.DELETE),
                                        Map.entry("tui.editor.deleteWordBackward", Key.Special.CTRL_W),
                                        Map.entry("tui.editor.deleteWordForward", Key.Special.ALT_D),
                                        Map.entry("tui.editor.deleteToLineStart", Key.Special.CTRL_U),
                                        Map.entry("tui.editor.deleteToLineEnd", Key.Special.CTRL_K),
                                        Map.entry("tui.editor.yank", Key.Special.CTRL_Y),
                                        Map.entry("tui.editor.yankPop", Key.Special.ALT_Y),
                                        Map.entry("tui.editor.undo", Key.Special.CTRL_UNDERSCORE),
                                        Map.entry("tui.input.newLine", Key.Special.NEWLINE),
                                        Map.entry("tui.input.submit", Key.Special.ENTER),
                                        Map.entry("tui.input.tab", Key.Special.TAB)));
    }

    private static Map<Key.Special, Key.Special> buildSelectorKeyRemaps(UserKeybindings keys) {
        return keys.remaps(Map.ofEntries(Map.entry("tui.select.up", Key.Special.UP),
                                        Map.entry("tui.select.down", Key.Special.DOWN),
                                        Map.entry("tui.select.pageUp", Key.Special.PG_UP),
                                        Map.entry("tui.select.pageDown", Key.Special.PG_DOWN),
                                        Map.entry("tui.select.confirm", Key.Special.ENTER),
                                        Map.entry("tui.select.cancel", Key.Special.ESC)));
    }

    private static Key remapSpecial(Key key, Map<Key.Special, Key.Special> remaps) {
        return key instanceof Key.Special special ? remaps.getOrDefault(special, special) : key;
    }

    private static void bind(GlobalKeyMap map, List<Key.Special> keys, Runnable action) {
        for (Key.Special key : keys)
            map.bind(key, action);
    }

    static boolean isReservedKey(Key key) {
        return key == Key.Special.ESC || key == Key.Special.DOUBLE_ESC || key == Key.Special.CTRL_C
                                        || key == Key.Special.ENTER || key == Key.Special.NEWLINE
                                        || key == Key.Special.BACKSPACE || key == Key.Special.TAB
                                        || key == Key.Special.PASTE_START;
    }

    /**
     * The chords an extension binding can actually fire: the image of {@link #toKeyChord} over the keys the decoder
     * produces, minus the ones the TUI reserves for itself and never routes onward. A binding outside this set is
     * unreachable, so it is refused at registration rather than listed in {@code /help} as a shortcut that does
     * nothing.
     */
    static Set<KeyChord> deliverableChords() {
        var out = new HashSet<KeyChord>();
        for (var key : Key.Special.values()) {
            if (isReservedKey(key))
                continue;
            KeyChord chord = toKeyChord(key);
            if (chord != null)
                out.add(chord);
        }
        return out;
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
            case CTRL_R -> new KeyChord(KeyChord.Key.R, KeyChord.Modifier.CTRL);
            case CTRL_T -> new KeyChord(KeyChord.Key.T, KeyChord.Modifier.CTRL);
            case CTRL_U -> new KeyChord(KeyChord.Key.U, KeyChord.Modifier.CTRL);
            case CTRL_W -> new KeyChord(KeyChord.Key.W, KeyChord.Modifier.CTRL);
            case CTRL_SHIFT_P -> new KeyChord(KeyChord.Key.P, KeyChord.Modifier.CTRL, KeyChord.Modifier.SHIFT);
            case CTRL_ALT_G -> new KeyChord(KeyChord.Key.G, KeyChord.Modifier.CTRL, KeyChord.Modifier.ALT);
            case CTRL_ALT_P -> new KeyChord(KeyChord.Key.P, KeyChord.Modifier.CTRL, KeyChord.Modifier.ALT);
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

    /**
     * Closes the topmost cancellable overlay: select picker, log viewer, one of the {@code selectors} (model/theme/
     * session/…), the completions popup, or the message browser, returning whether one was closed. The single place
     * that guarantees every overlay is dismissable with Esc or a single Ctrl+C, so none can trap the user into
     * choosing.
     */
    private boolean cancelActiveOverlay() {
        if (selector.active()) {
            selector.cancel();
            return true;
        }
        if (logViewer.active()) {
            logViewer.close();
            return true;
        }
        if (selectors.anyActive()) {
            selectors.deactivateAll();
            clearStatusOverride();
            promptEditor.setText(promptStash); // give back the draft the selector borrowed the line from
            promptStash = "";
            return true;
        }
        if (promptEditor.hasCompletions()) {
            promptEditor.clearCompletions();
            return true;
        }
        if (messageBrowser.isActive()) {
            messageBrowser.cancel();
            return true;
        }
        return false;
    }

    /** Test seam: whether any cancellable overlay is currently open (mirrors {@link #cancelActiveOverlay}'s set). */
    boolean anyOverlayActive() {
        return selector.active() || logViewer.active() || selectors.anyActive() || promptEditor.hasCompletions()
                                        || messageBrowser.isActive();
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
            // A visible cut mark: without it the transcript just stops mid-sentence, and on a later re-read there is
            // no way to tell an interrupt from a crash or a model that trailed off.
            appendPlain(List.of(theme.styled("statusText", "■ interrupted")));
            shellCommands.interruptProcess();
            restoreQueuedFollowUpsAfterAbort();
        } else {
            setTransientStatus("Press Esc again to interrupt", 2500);
        }
        redrawPrompt();
    }

    private void restoreQueuedFollowUpsAfterAbort() {
        var pending = session.drainFollowUps();
        if (pending.isEmpty())
            return;
        String restored = String.join("\n\n", pending);
        promptEditor.setText(promptEditor.isEmpty() ? restored : promptEditor.text() + "\n\n" + restored);
        setStatusOverride("Interrupted; restored queued messages to editor");
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
        suspendTui(true);
    }

    /**
     * @param freshLine
     *            advance to a new line on suspend. True for programs that print to stdout (their output then starts
     *            below the prompt). False for alternate-screen programs (fzf): the screen (and cursor) are restored on
     *            exit, so an extra newline would leave the cursor one row below where {@link PromptLayout#eraseCurrent}
     *            expects it, so the in-place erase misses the top separator and a stale {@code ─} rule accumulates each
     *            time.
     */
    public void suspendTui(boolean freshLine) {
        if (suspendDepth++ > 0)
            return;
        // Hand a clean terminal to the suspended program / shell-out: end sync (?2026l) + restore autowrap (?7h, which
        // the render loop turns off while drawing) + show cursor + reset colors. resumeTui re-enters raw mode and the
        // next frame re-manages these, so nothing here needs undoing on resume.
        String kitty = KittyKeyboard.DISABLE; // hand the shell-out a terminal it understands
        terminal.writer().print(freshLine
                                        ? kitty + "\033[?2026l\033[?7h\033[?25h\033[0m\r\n"
                                        : kitty + "\033[?2026l\033[?7h\033[?25h\033[0m");
        terminal.writer().flush();
        if (originalAttributes != null)
            terminal.setAttributes(originalAttributes);
    }

    /**
     * Ctrl+Z job-control suspend. Raw mode turns off ISIG, so the kernel never turns Ctrl+Z into SIGTSTP; we do it
     * ourselves: drop to cooked mode, stop the process (the shell prints "Suspended"), then re-enter raw mode and
     * repaint when {@code fg} continues us. The stop blocks here until SIGCONT; the handler installed by
     * {@link #installSigcontHandler()} then repairs the modes deterministically if the shell's restore raced us.
     */
    private void suspendProcess() {
        if (Platform.isWindows())
            return; // no SIGTSTP / job control on Windows
        suspendTui();
        try {
            raiseSigtstp();
        } catch (Throwable ignored) {
            // platform without SIGTSTP; fall through and just restore the prompt
        } finally {
            resumeTui();
        }
    }

    /**
     * Deliver SIGTSTP to this process for a real job-control stop. GraalVM's own runtime installs a SIGTSTP dispatcher
     * that intercepts the signal before any {@code sun.misc.Signal} disposition applies (confirmed by native stderr
     * tracing: a Java handler registered for TSTP never runs, and the process never stops). So we bypass the JVM signal
     * machinery entirely and ask the kernel to stop us: a bare {@code kill -TSTP <pid>} via {@code sh}, which needs no
     * registered handler and always takes the default stop-the-process action (the shell prints "Suspended").
     * {@code waitFor()} returns after {@code fg}'s SIGCONT wakes us. No JNA (jnidispatch is not embedded in native
     * images).
     */
    private static void raiseSigtstp() throws Exception {
        long pid = ProcessHandle.current().pid();
        // No `sh -c`: ProcessBuilder execs the binary off PATH, so the shell builtin never enters it. One fork per
        // Ctrl+Z, and suspend is not a hot path.
        int rc = new ProcessBuilder("kill", "-TSTP", Long.toString(pid)).inheritIO().start().waitFor();
        if (rc != 0)
            throw new IllegalStateException("kill -TSTP exited " + rc);
    }

    /**
     * Re-assert the TUI's terminal state — raw mode, Kitty keyboard flags, bracketed paste — without touching suspend
     * bookkeeping or repainting. Idempotent and safe to call from any thread, including the SIGCONT handler's.
     *
     * The pre-TUI attributes are snapshotted on the first call (startup) and never replaced afterwards: a resume that
     * raced the shell must not capture whatever transient modes the tty happens to be in, or the next suspend would
     * "restore" garbage.
     */
    private void assertTerminalModes() {
        try {
            Attributes attrs = terminal.enterRawMode();
            if (originalAttributes == null)
                originalAttributes = attrs;
            // suspendTui popped the Kitty flags; the shell may also have toggled bracketed paste. Put both back.
            terminal.writer().print(KittyKeyboard.ENABLE + "\033[?2004h");
            terminal.writer().flush();
        } catch (Exception ignored) {}
    }

    /**
     * Repair the terminal after {@code fg}. When the shell resumes a job it first restores the tty modes it saved when
     * the job stopped — and that restore races our own {@link #resumeTui()}. If the shell lands last, the tty stays
     * cooked with ICRNL on and Enter decodes as LF (insert newline) instead of CR (submit). SIGCONT is delivered
     * strictly after the shell's tcsetattr, so re-asserting here always lands last.
     *
     * The handler only fires for our own suspends (depth &gt; 0): after a foreign stop the shell restores our raw state
     * by itself, and a tcsetattr from a process that is still backgrounded would just re-stop it via SIGTTOU.
     */
    void installSigcontHandler() { // package-private for tests
        if (Platform.isWindows())
            return; // no job-control signals on Windows; suspendProcess() is disabled there too
        // Chain, do not replace. handle() installs our disposition and hands back whatever held CONT before, and
        // dropping that return value silently unregisters it -- for a signal we do not own and only want to observe.
        // Today's previous holder is the default, but "we are the only ones interested in CONT" is not something this
        // class can know: JLine picks a terminal provider at runtime, and the JDK and GraalVM both install their own.
        //
        // Atomic, not a one-element array: the handler reads this from the signal thread, so the write has to be
        // published to it. A CONT landing between handle() returning and the set below finds it empty and skips the
        // chain -- a startup-only window, where the previous holder is the default and suspendDepth is 0, so both
        // halves of the handler are no-ops anyway.
        var previous = new AtomicReference<sun.misc.SignalHandler>();
        try {
            previous.set(sun.misc.Signal.handle(new sun.misc.Signal("CONT"), sig -> {
                if (suspendDepth > 0)
                    assertTerminalModes();
                chainTo(previous.get(), sig);
            }));
        } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {
            // no SIGCONT on this platform; the inline resumeTui() path still covers Ctrl+Z
        }
    }

    /**
     * Hands a signal on to the disposition that held it before us.
     *
     * <p>
     * {@code SIG_DFL} and {@code SIG_IGN} name a kernel disposition rather than a callable Java handler, and invoking
     * either throws, so they are passed over: the kernel still applies them. Anything else is a real handler and gets
     * the signal it registered for, inside a catch, because a handler we do not own must not take the resume down with
     * it.
     */
    private static void chainTo(sun.misc.SignalHandler prior, sun.misc.Signal sig) {
        if (prior == null || prior == sun.misc.SignalHandler.SIG_DFL || prior == sun.misc.SignalHandler.SIG_IGN)
            return;
        try {
            prior.handle(sig);
        } catch (Throwable ignored) {
            // not ours to fix, and not ours to die from
        }
    }

    public void resumeTui() {
        if (suspendDepth == 0 || --suspendDepth > 0)
            return;
        assertTerminalModes();
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

    /**
     * Ctrl+T: fzf-pick a file and insert its <em>raw path</em> (no {@code @}), for putting a path in the message text.
     */
    private void pickPathWithFzf() {
        if (!activity.isBusy() && FzfFilePicker.available())
            pickFileWithFzf(false);
    }

    /**
     * Opens fzf (suspending the TUI, like the editor) to pick a file. When {@code asReference}, inserts an
     * {@code @}-reference (quoted/escaped, resolved as a file); otherwise the raw path (a leading {@code ./} trimmed)
     * for use as plain text in the message.
     */
    private void pickFileWithFzf(boolean asReference) {
        Path out = null;
        try {
            out = Files.createTempFile("pijota-fzf-", ".txt");
            suspendTui(false); // fzf uses the alternate screen → no fresh-line, or a stale separator is left on resume
            String shell = settings != null ? settings.effectiveShellPath() : Platform.defaultShell();
            ProcessRunner.command(shell, Platform.shellCommandFlag(shell), FzfFilePicker.script(out, theme))
                                            .runInteractive();
            resumeTui();
            // Selection, typed-query fallback (no-match Enter), or "" on cancel; typed input is never discarded.
            String text = FzfFilePicker.insertionFrom(Files.readString(out, StandardCharsets.UTF_8), asReference);
            if (!text.isEmpty())
                promptEditor.insertText(text + " ");
        } catch (Exception e) {
            resumeTui();
            setStatusOverride("fzf error: " + e.getMessage());
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {}
            }
            redrawPrompt();
        }
    }

    private void openPromptInEditor() {
        if (activity.isBusy()) {
            setTransientStatus("Editor unavailable while busy", 2500);
            redrawPrompt();
            return;
        }
        try {
            suspendTui(false); // the editor takes over the alternate screen like fzf → no fresh-line, or a stale ─
                               // accumulates
            String editor = System.getenv().getOrDefault("EDITOR", Platform.isWindows() ? "notepad" : "vi");
            String shell = settings != null ? settings.effectiveShellPath() : Platform.defaultShell();
            var result = externalEditor.edit(shell, editor, promptEditor.text());
            resumeTui();
            if (result.changed()) {
                promptEditor.setText(result.content());
            } else {
                setTransientStatus("Editor exited with code " + result.exitCode(), 3500);
            }
        } catch (Exception e) {
            resumeTui();
            setStatusOverride("Editor error: " + e.getMessage());
        } finally {
            redrawPrompt();
        }
    }

    /** Heads-up when the current model can't read some attached media; it'll be dropped to a note at send time. */
    private void warnUnreadableAttachments(List<Message.Attachment> attachments) {
        if (attachments.isEmpty())
            return;
        var unreadable = attachments.stream().map(a -> Modality.fromMime(a.mime())).filter(m -> m != Modality.TEXT
                                        && !ModelRegistry.supports(session.llm().provider(), session.llm().model(), m))
                                        .map(m -> m.name().toLowerCase(Locale.ROOT)).distinct().toList();
        if (!unreadable.isEmpty())
            appendPlain(List.of(theme.styled("statusText", "⚠ " + session.llm().model() + " can't read "
                                            + String.join("/", unreadable) + ", sending text only")));
    }

    private void handleSubmit(String text) {
        String cleaned = Util.stripSystemReminders(text);
        // A leading space escapes command/shell syntax: " /review …" or " !cmd" is sent to the model as a literal
        // message; only an unindented "/" or "!" is treated as a command.
        boolean literal = !cleaned.isEmpty() && Character.isWhitespace(cleaned.charAt(0));
        String submitted = cleaned.strip();
        if (submitted.isBlank())
            return;
        var expansion = fileRefs.expand(submitted);
        submitted = expansion.text();
        var attachments = expansion.attachments(); // @image files pulled out for vision models
        String answer = submitted;
        if (dialogs.submitQuestionAnswer(answer, () -> appendPlain(promptEcho(answer))))
            return;
        String payload = submitted;
        session.stripSystemRemindersFromContext();
        if (!literal && isBareHelp(payload)) {
            commandController.handle("/help");
            return;
        }
        if (!literal && payload.startsWith("/")) {
            commandController.handle(payload.strip());
            return;
        }
        if (!literal && isVimQuit(payload)) { // chef's kiss: :q (and its cousins) quit pijota, for Vim muscle memory
            commandController.handle("/quit");
            return;
        }
        if (!literal && payload.startsWith("!!")) {
            shellCommands.run(payload.substring(2).strip(), false);
            return;
        }
        if (!literal && payload.startsWith("!")) {
            shellCommands.run(payload.substring(1).strip(), true);
            return;
        }
        warnUnreadableAttachments(attachments);
        appendPlain(promptEcho(submitted));
        if (activity.isBusy() && session.isBusy()) {
            if (!attachments.isEmpty()) // followUp steers with text only; attachments can't ride along mid-turn
                appendPlain(List.of(theme.styled("toolError",
                                                "attachments aren't sent mid-turn. Resend after this turn")));
            session.followUp(payload);
            appendPlain(List.of(theme.styled("statusText",
                                            "queued follow-up (" + session.queuedFollowUps().size() + ")")));
            redrawPrompt();
            return;
        }
        exitGate.disarm();
        long gen = beginBusy("waiting for model");
        redrawPrompt();
        agentExecutor.submit(() -> {
            try {
                streamRenderer.reset();
                session.prompt(payload, attachments).join();
            } catch (CompletionException e) {
                // The turn already rendered the failure via the AgentEvent.Error event; just forward to extensions
                // and reflect it in the status line (no duplicate "Exception:" line).
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                notifyExtensionsOfException(cause);
                setStatusOverride("Error: " + ErrorUtil.message(cause));
            } catch (Throwable t) {
                notifyExtensionsOfException(t);
            } finally {
                showSessionWarning();
                endBusy(gen);
                redrawPrompt();
            }
        });
    }

    static boolean isBareHelp(String payload) {
        return payload.equalsIgnoreCase("help");
    }

    /**
     * Vim quit muscle memory: {@code :q} and its write/quit-all cousins exit pijota (autosaves, so :wq == :q). Input is
     * the already-stripped submission; a leading space is the literal escape (handled by the caller), so " :q" is text.
     */
    static boolean isVimQuit(String payload) {
        return switch (payload) {
            case ":q", ":q!", ":wq", ":wq!", ":x", ":quit", ":qa", ":qa!" -> true;
            default -> false;
        };
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

    boolean runExtensionCommand(SlashCommand extCmd, String input) {
        if (extCmd == null || extCmd.handler() == null)
            return false;
        extCmd.handler().accept(SlashCommand.argsOf(input));
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
        // No transient line: the footer's own effort chip is the feedback. It sits a row below where the eye already
        // is, and it carries the level's colour, which says "you are at max" faster than a second row repeating the
        // word does.
        redrawPrompt();
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
        showThemeSelector("");
    }

    void showThemeSelector(String arg) {
        themeCommands.showThemeSelector(arg);
    }

    @Override
    public CompletableFuture<String> askQuestions(List<SlashCommandTool.Question> questions) {
        return dialogs.askQuestions(questions);
    }

    void showModelSelector() {
        modelCommands.showModelSelector();
    }

    void refreshModels() {
        // ModelRefresher.refresh() is a multi-provider network call; run it off the input thread so the UI never
        // freezes (the input loop stays free to type / Esc); feedback is printed immediately, the result when it lands.
        if (refuseWhileBusy("/refresh"))
            return;
        appendPlain(List.of(theme.styled("statusText", "refreshing models…")));
        agentExecutor.submit(modelCommands::refreshModels);
    }

    void reloadRuntimeResources() {
        appendPlain(List.of(theme.styled("statusText", "reloading model metadata…"), theme.styled("statusText",
                                        "keybindings, extensions, skills, prompts, and themes reload on restart")));
        refreshModels();
    }

    void showModelSelector(List<ModelInfo> models) {
        modelCommands.showModelSelector(models);
    }

    void showSessionPicker(String query) {
        if (refuseWhileBusy("/sessions"))
            return;
        var sessions = session.resumableSessions();
        if (sessions.isEmpty()) {
            appendStatus("no sessions found");
            return;
        }
        var items = SessionItem.fromPaths(sessions);
        selectors.show(TuiSelectors.Slot.SESSION, items, query);
        promptEditor.setText(query == null ? "" : query);
        setStatusOverride("Sessions: Enter load, Ctrl+X delete");
        redrawPrompt();
    }

    private boolean handleSessionPickerAction(Key key) {
        if (selectors.<SessionItem>selected(TuiSelectors.Slot.SESSION).isEmpty())
            return false;
        // Not Ctrl+D. That byte is also what the decoder reports for EOF (InputDecoder: `case -1, 4`), and this
        // runs before the global keymap, so with the picker open a closed stdin asked to delete a session instead of
        // quitting -- and the universal quit key could not quit. Ctrl+X is decoded already and bound to nothing else.
        if (key == Key.Special.CTRL_X) {
            deleteSelectedSession();
            return true;
        }
        return false;
    }

    private void refreshSessionPicker(String status) {
        var items = SessionItem.fromPaths(session.resumableSessions());
        if (items.isEmpty()) {
            selectors.deactivate(TuiSelectors.Slot.SESSION);
            clearStatusOverride();
            appendPlain(List.of(theme.styled("statusText", status + "; no sessions remain")));
            redrawPrompt();
            return;
        }
        selectors.refresh(TuiSelectors.Slot.SESSION, items);
        setStatusOverride("Sessions: " + status);
        redrawPrompt();
    }

    private void deleteSelectedSession() {
        var chosen = selectors.<SessionItem>selected(TuiSelectors.Slot.SESSION).orElse(null);
        if (chosen == null)
            return;
        dialogs.confirm("delete session '" + chosen.description() + "'?").thenAccept(yes -> {
            if (!yes)
                return;
            try {
                boolean trashed = session.deleteSession(chosen.path().toString());
                refreshSessionPicker((trashed ? "moved to trash " : "deleted permanently ") + chosen.description());
            } catch (IOException e) {
                appendPlain(List.of(theme.styled("toolError", "delete failed: " + e.getMessage())));
                redrawPrompt();
            }
        });
    }

    private void showSessionPicker() {
        showSessionPicker("");
    }

    private void confirmSessionSelection() {
        var chosen = selectors.<SessionItem>selected(TuiSelectors.Slot.SESSION).orElse(null);
        if (chosen == null)
            return;
        selectors.deactivate(TuiSelectors.Slot.SESSION);
        clearStatusOverride();
        promptEditor.setText("");
        try {
            session.load(chosen.path());
            restoreRuntimeStateFromSession();
            refreshSessionName();
            appendPlain(List.of(theme.styled("statusText", "loaded session " + chosen.name())));
            appendLoadRecap();
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
        showHelpOverlay(false);
    }

    /**
     * @param shortcutsFirst
     *            /hotkeys lists keyboard shortcuts before commands; /help lists commands first.
     */
    void showHelpOverlay(boolean shortcutsFirst) {
        // Render help in the scrollable log viewer (one modal viewer for /help and /logs) so the full command list
        // and shortcuts scroll and fit the terminal, rather than being capped.
        logViewer.show("help", HelpOverlay.content(commands.helpLines(), extensionKeybindings.values(),
                                        shortcutsFirst));
        redrawPrompt();
    }

    /** Switch via a model alias (e.g. {@code /model alias/fast}), resolved from settings {@code "aliases"}. */
    void switchToAlias(String spec) {
        String name = ModelRegistry.parseProviderModel(spec).model();
        if (ModelRegistry.CURRENT_ALIAS.equalsIgnoreCase(name)) {
            // alias/current is the dynamic, read-only alias for the live model; switching to it is a no-op.
            appendPlain(List.of(theme.styled("statusText", "alias/current = " + provider + "/" + model)));
            return;
        }
        var target = ModelRegistry.resolveAlias(name, settings);
        if (target == null) {
            appendPlain(List.of(theme.styled("toolError", "unknown alias \"" + name
                                            + "\". Define it under \"aliases\" in settings.json")));
            return;
        }
        long ctx = ModelRegistry.resolveContextWindow(target.provider(), target.model());
        switchToModel(new ModelInfo(target.model(), target.provider(), target.provider() + "/" + target.model(), ctx));
    }

    void switchToModel(ModelInfo chosen) {
        String key = settings != null ? settings.resolveApiKey(chosen.provider()) : null;
        if (key == null || (key.isBlank() && SettingsManager.requiresApiKey(chosen.provider()))) {
            appendPlain(List.of(theme.styled("toolError", "no API key for " + chosen.provider())));
            return;
        }
        applyModel(chosen.provider(), chosen.id(), key, chosen.contextWindow(), true);
        appendPlain(List.of(theme.styled("statusText",
                                        provider + "/" + chosen.id() + "  " + ModelDisplay.summary(chosen))));
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
        if (refuseWhileBusy("/compact"))
            return;
        appendPlain(List.of(theme.styled("toolLabel",
                                        "/compact" + (instructions.isBlank() ? "" : ": " + instructions))));
        long gen = beginBusy("compacting");
        streamRenderer.reset();
        streamRenderer.clearCompactionStream();
        redrawPrompt();
        agentExecutor.submit(() -> {
            try {
                if (!session.compact(instructions)) {
                    // Say why nothing happened: compaction is threshold-gated, so a small context has nothing to shed.
                    appendPlain(List.of(theme.styled("statusText", "nothing to compact yet, context is "
                                                    + promptPresenter.tokenStatus() + "; compaction kicks in near the "
                                                    + "context limit")));
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

    void branchSummary(String instructions) {
        sessionCommands.branchSummary(instructions);
    }

    private void refreshSessionName() {
        try {
            sessionName = session.sessionName();
        } catch (IOException ignored) {
            sessionName = null;
        }
    }

    void exportSession(String input) {
        sessionCommands.exportSession(input);
    }

    void importSession(String input) {
        sessionCommands.importSession(input);
    }

    void saveSession(String input) {
        sessionCommands.saveSession(input);
    }

    void loadSession(String input) {
        if (refuseWhileBusy("/load"))
            return;
        String path = input.length() > 5 ? input.substring(5).trim() : "";
        if (path.isBlank()) {
            showSessionPicker("");
            return;
        }
        try {
            var file = session.resolveSession(path);
            if (!session.messages().isEmpty())
                appendPlain(List.of(theme.styled("statusWarn", "loading session. Current messages will be replaced")));
            int count = session.load(file);
            restoreRuntimeStateFromSession();
            refreshSessionName();
            if (count == 0) {
                appendPlain(List.of(theme.styled("statusWarn", "no messages found in session file")));
                return;
            }
            appendPlain(List.of(theme.styled("statusText",
                                            "loaded " + count + " messages from " + file.getFileName())));
            appendLoadRecap();
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "load failed: " + e.getMessage())));
        }
    }

    // After a load the append-only UI doesn't reprint the whole conversation, so show the last exchange (one truncated
    // line each), enough to orient the user on where they left off without dumping the history.
    private void appendLoadRecap() {
        var users = session.userMessages();
        String lastUser = users.isEmpty() ? null : users.get(users.size() - 1).content();
        String lastAssistant = session.lastAssistantContent();
        var lines = new ArrayList<String>();
        if (lastUser != null && !lastUser.isBlank())
            lines.add(theme.styled("dim", "  ↳ you · " + Util.ellipsize(lastUser.replace('\n', ' ').strip(), 74)));
        if (lastAssistant != null && !lastAssistant.isBlank())
            lines.add(theme.styled("dim", "  ↳ reply · "
                                            + Util.ellipsize(lastAssistant.replace('\n', ' ').strip(), 74)));
        if (!lines.isEmpty())
            appendPlain(lines);
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
        showTree("");
    }

    void showTree(String arg) {
        if (session.sessionFile() == null) {
            appendPlain(List.of(theme.styled("statusWarn", "no active session")));
            return;
        }
        if (arg != null && arg.strip().toLowerCase(Locale.ROOT).startsWith("summarize")) {
            String instructions = arg.strip().length() > "summarize".length()
                                            ? arg.strip().substring("summarize".length()).strip()
                                            : "";
            sessionCommands.branchSummary(instructions);
        }
        try {
            appendPlain(new TreeRenderer(theme).render(session.treeEntries(), session.branchEntries(),
                                            session.leafId()));
            showTreeSelector();
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "tree: " + e.getMessage())));
        }
    }

    private void showTreeSelector() throws IOException {
        var entries = session.treeEntries();
        if (entries.isEmpty())
            return;
        String leaf = session.leafId();
        var items = new ArrayList<TreeEntryItem>();
        for (var entry : entries) {
            String id = String.valueOf(entry.getOrDefault("id", ""));
            if (!id.isBlank() && !TreeEntryItem.isInternalMetadata(entry))
                items.add(TreeEntryItem.from(entry, id.equals(leaf)));
        }
        if (items.isEmpty())
            return;
        selectors.show(TuiSelectors.Slot.TREE, items);
        selectors.<TreeEntryItem>selectFirst(TuiSelectors.Slot.TREE, TreeEntryItem::current);
        setStatusOverride("Tree: type to search, Enter jumps to entry, Esc cancels");
        redrawPrompt();
    }

    private void confirmTreeSelection() {
        var chosen = selectors.<TreeEntryItem>selected(TuiSelectors.Slot.TREE).orElse(null);
        if (chosen == null)
            return;
        selectors.deactivate(TuiSelectors.Slot.TREE);
        clearStatusOverride();
        try {
            int count = chosen.user() ? session.branchToParentOf(chosen.parentId()) : session.branchTo(chosen.id());
            restoreRuntimeStateFromSession();
            if (chosen.user())
                promptEditor.setText(chosen.content());
            appendPlain(List.of(theme.styled("statusText", "jumped to " + chosen.role() + " entry · " + count
                                            + " messages in context"
                                            + (chosen.user() ? " · restored prompt for editing" : ""))));
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "tree jump failed: " + e.getMessage())));
        }
    }

    /**
     * {@code /remote-control}: pick a remote channel (web / Telegram) to drive this live session, or stop the active
     * one.
     */
    void showRemoteControl() {
        var items = new ArrayList<RemoteOptionItem>();
        if (remoteControl.active()) {
            items.add(new RemoteOptionItem("show", "Show link & QR", remoteControl.summary()));
            items.add(new RemoteOptionItem("stop", "Stop remote control", "active: " + remoteControl.summary()));
        } else {
            // Ordered safest-first: Tailscale and Tunnel are encrypted and lead, so a new user lands on a safe
            // default. Plain Web (cleartext token) is last and carries its own warning.
            items.add(new RemoteOptionItem("tailscale", "Tailscale (your devices only)",
                                            "https · encrypted · most private · needs tailscaled"));
            items.add(new RemoteOptionItem("web-tunnel", "Tunnel (public internet)",
                                            "https · cloudflared · Cloudflare sees the token"));
            items.add(new RemoteOptionItem("telegram", "Telegram", "drive from your phone · needs a bot token"));
            items.add(new RemoteOptionItem("web", "Web (same wifi only)", "cleartext token · trusted networks only"));
        }
        selectors.show(TuiSelectors.Slot.REMOTE, items);
        setStatusOverride("Remote control: Enter to choose, Esc cancels");
        redrawPrompt();
    }

    /**
     * Extension status lines, plus a persistent reminder (and security cue) whenever remote control is driving this
     * session.
     */
    private static final int STATUS_COLLAPSED_LINES = 3; // status rows shown before "… N more"; Ctrl+O unrolls
    private volatile boolean statusExpanded;

    private void toggleStatusExpanded() {
        statusExpanded = !statusExpanded;
        redrawPrompt();
    }

    private List<String> statusLinesWithRemote() {
        var all = new ArrayList<String>(extensionLinesSupplier.get());
        if (remoteControl != null && remoteControl.active())
            all.add("🔗 remote control active · " + remoteControl.summary() + " · /remote-control to manage");
        return collapse(PromptPresenter.mergeExtensionLines(all), statusExpanded, STATUS_COLLAPSED_LINES);
    }

    /**
     * Caps the status lines to {@code cap} with a "… N more · Ctrl+O" affordance, or returns them all when expanded.
     */
    static List<String> collapse(List<String> all, boolean expanded, int cap) {
        if (all.size() <= cap)
            return all;
        if (expanded) {
            var out = new ArrayList<>(all);
            out.add(Keys.label("… Ctrl+O to collapse"));
            return out;
        }
        var out = new ArrayList<>(all.subList(0, cap));
        out.add(Keys.label("… " + (all.size() - cap) + " more · Ctrl+O to expand"));
        return out;
    }

    private void confirmRemoteSelection() {
        var selected = selectors.selected(TuiSelectors.Slot.REMOTE);
        selectors.deactivate(TuiSelectors.Slot.REMOTE);
        clearStatusOverride(); // drop the "Remote control · Enter to choose…" hint once a choice is made
        if (selected.isEmpty()) {
            redrawPrompt();
            return;
        }
        switch (((RemoteOptionItem) selected.get()).id()) {
            // Starting a channel can do network I/O (Telegram getMe + setMyCommands) or bind a socket; run it off the
            // input thread so the UI never freezes; the banner is appended when it lands.
            case "web" -> startRemoteAsync(remoteControl::startWeb);
            case "web-tunnel" -> startRemoteAsync(remoteControl::startWebTunnel);
            case "tailscale" -> startRemoteAsync(remoteControl::startTailscale);
            case "telegram" -> startRemoteAsync(remoteControl::startTelegram);
            case "show" -> appendPlain(remoteControl.info()); // instant: re-show the active channel's link & QR
            case "stop" -> appendPlain(remoteControl.stop()); // fast: detach + close
            default -> {}
        }
        redrawPrompt();
    }

    /**
     * Run a remote-control start (which may block on network/socket setup) off the input thread; print its banner
     * after. Uses a dedicated thread, NOT {@code agentExecutor} (which is single-threaded); a network-blocking start
     * must not queue behind a running turn, and we don't want it to block one either.
     */
    private void startRemoteAsync(Supplier<List<String>> start) {
        appendPlain(List.of(theme.styled("statusText", "starting remote control…")));
        Thread.ofPlatform().daemon().name("pijota-remote-start").start(() -> {
            appendPlain(start.get()); // appendPlain marshals to the terminal thread, so it's safe from here
            redrawPrompt();
        });
    }

    void forkSession(String input) {
        if (refuseWhileBusy("/fork"))
            return;
        if (session.sessionFile() == null) {
            appendPlain(List.of(theme.styled("statusWarn", "no active session")));
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
        setStatusOverride("Select message to fork from; type to search, Enter forks, Esc cancels");
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

    /**
     * Takes the most recent message back and puts it in the editor, wherever it currently is.
     *
     * <p>
     * Queued follow-ups first, newest first, then the last committed turn. They were two commands, /undo and /dequeue,
     * whose availability windows never overlapped -- /undo refused while busy, /dequeue only meant anything while busy
     * -- so the choice between them carried no information and only asked the user to know which list pijota happened
     * to be keeping.
     *
     * <p>
     * It never escalates. When a withdraw finds nothing while the turn is still running, the message is already in
     * flight, and the answer is to say so: falling through would undo an older, committed turn instead, which is a far
     * more destructive thing than the one that was asked for.
     */
    void undoLastTurn() {
        var queued = session.withdrawNewestFollowUp();
        if (queued.isPresent()) {
            promptEditor.setText(queued.get());
            appendPlain(List.of(theme.styled("statusText",
                                            "took back the queued follow-up; edit and press Enter to resend")));
            redrawPrompt();
            return;
        }
        if (refuseWhileBusy("/undo"))
            return;
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
            case AgentEvent.TurnStart ignored -> {
                toolsInProgress.clear(); // evict any leftovers from a prior interrupted/aborted turn
                interruptedCalls.clear();
                promptEditor.clearOffer(); // a suggestion described the conversation as it stood before this turn
                streamRenderer.reset();
                streamingContent = false;
                activity.setPhase("waiting for model");
                redrawPrompt();
            }
            // The one signal that says the model is still producing rather than merely still connected. Text and
            // thinking already grow on screen; a tool call's arguments used to arrive with nothing to show for them.
            case AgentEvent.ToolCallProgress(String toolName, int chars) -> {
                activity.setPhase(TuiCopy.toolProgress(toolName, chars));
                redrawPrompt();
            }
            case AgentEvent.AssistantMessage(Message.Assistant msg, var usage) -> {
                if (usage == null)
                    activity.setPhase(null);
                streamingContent = usage == null; // reserve the live-tail row for the whole streaming turn
                String content = msg.content() == null ? "" : msg.content();
                boolean committed = false;
                if (msg.thinking() != null && !msg.thinking().isBlank()) {
                    if (showThinking())
                        committed = streamRenderer.streamThinking(msg.thinking());
                    else
                        streamRenderer.recordThinking(msg.thinking()); // closeThinking explains the pause with ∴
                }
                // Assistant messages always stream. Message renderers apply to custom messages (handled by the
                // CustomMessage case below), and never to assistant content, so they must not disable streaming.
                committed = streamRenderer.streamContent(content) || committed;
                if (usage != null) {
                    streamRenderer.closeThinking();
                    streamRenderer.flush();
                    redrawPrompt(); // final frame: clears the now-committed tail, unthrottled
                } else if (committed) {
                    // A line just committed: a full redraw keeps the committed line and the region (with the refreshed
                    // tail) in one consistent frame; otherwise the stale tail makes the prompt jitter up then down.
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
                    // The running-tool tail draws this detail, and it is built from arguments the model chose.
                    toolsInProgress.put(toolCallId, new ToolInProgress(name, TerminalText.printable(ToolLabels.detail(
                                                    name, args, rawCwd,
                                                    settings != null ? settings.pathDisplay() : PathDisplay.COMPACT,
                                                    ToolLabels.FULL_COMMAND)), args, System.currentTimeMillis()));
                pendingEdit.onToolStart(name, args);
                // No phase: the running-tool tail one row up already names the tool, with its arguments and its own
                // elapsed. "tool bash" beside it was the same word twice with two timers of different scope between
                // them, which reads as two things running.
                activity.setPhase(null);
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
                if (msg.toolCallId() != null && interruptedCalls.remove(msg.toolCallId()))
                    break; // already reported as interrupted; its exit-143 result would say the same thing twice
                ToolInProgress started = msg.toolCallId() != null ? toolsInProgress.remove(msg.toolCallId()) : null;
                Supplier<String> args = started != null ? started.args() : null;
                long elapsedMs = started != null ? System.currentTimeMillis() - started.startedAtMs() : 0;
                Tool.ToolResultView rendered = renderToolResultView(msg);
                if (isEditWrite(msg)) {
                    // The TUI renders the diff itself (the preview above, or the DiffRenderer below). Drop the tool
                    // view's own diff body so the change isn't shown twice; keep only its one-line summary.
                    if (rendered != null)
                        rendered = Tool.ToolResultView.of(rendered.summary());
                    if (pendingEdit.hasEditResult() && !pendingEdit.previewShown())
                        appendPlain(new DiffRenderer(theme).render(msg.toolName(), pendingEdit.filePath(),
                                                        pendingEdit.oldText(), pendingEdit.newText(),
                                                        terminal.getWidth()));
                }
                String glyph = session.tool(msg.toolName()).map(Tool::glyph).orElse(ToolGlyphs.AGENT);
                appendPlain(ToolResultLines.compose(msg, rendered, args, theme, rawCwd,
                                                settings != null ? settings.pathDisplay() : PathDisplay.COMPACT,
                                                elapsedMs, glyph, blockWidth()));
                streamRenderer.separateNextContent(); // the answer resumes with one blank line of air
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
            case AgentEvent.CompactionStreamEnd ignored -> {
                streamRenderer.flush();
                streamRenderer.reset();
            }
            case AgentEvent.CustomMessage(Message.Custom custom) -> {
                // (see customMessageLines)
                streamRenderer.flush();
                // Some custom entries are state snapshots persisted for reload (e.g. goal status), shown via a status
                // line rather than the transcript; skip them so they don't print once per state change.
                if (!custom.isSilent()) {
                    if (session.extensionRegistry().hasMessageRenderers()) {
                        var rendered = session.extensionRegistry().renderMessage(custom);
                        if (rendered.isPresent()) {
                            appendPlain(List.of(theme.styled("customMessageLabel", custom.customType())));
                            appendPlain(rendered.get());
                        } else {
                            appendPlain(customMessageLines(custom));
                        }
                    } else {
                        appendPlain(customMessageLines(custom));
                    }
                }
                redrawPrompt();
            }
            case AgentEvent.TurnEnd ignored -> closeAbandonedTools();
            case AgentEvent.Error(Throwable t) -> {
                closeAbandonedTools();
                String msg = ErrorUtil.message(t);
                appendPlain(List.of(theme.styled("toolError", "error: " + msg)));
                // A bad credential is a dead end the user can fix in one command; say which one.
                String lower = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
                if (lower.contains("api key") || lower.contains("unauthorized") || lower.contains("invalid_api_key")
                                                || lower.contains("authentication"))
                    appendPlain(List.of(theme.styled("statusText",
                                                    "run /login " + session.llm().provider() + " to update the key")));
            }
            case AgentEvent.Retry(int attempt, int maxAttempts, long delayMs, String why) ->
                appendPlain(List.of(theme.styled("statusText", Util.retryLine(attempt, maxAttempts, delayMs, why))));
            case AgentEvent.HistoryChanged ignored -> seedPromptHistoryFromSession(); // /load, /new, /undo, /fork
            default -> {}
        }
    }

    /**
     * A tool whose result never arrived (interrupt, turn failure) still gets its header committed, as a failure with
     * "interrupted" for its metadata, so no block is ever left dangling and the live tail line has a resolution.
     */
    private void closeAbandonedTools() {
        if (toolsInProgress.isEmpty())
            return;
        var abandoned = toolsInProgress.entrySet().stream()
                                        .sorted(Comparator.comparingLong(e -> e.getValue().startedAtMs())).toList();
        abandoned.forEach(e -> interruptedCalls.add(e.getKey()));
        toolsInProgress.clear();
        String dot = theme.styled("commandMeta", " · ");
        for (var entry : abandoned) {
            var tool = entry.getValue();
            var line = new StringBuilder(theme.styled("toolError", ToolGlyphs.FAILED)).append(' ')
                                            .append(theme.styled("toolLabel", ToolResultLines.label(tool.name())));
            if (!tool.detail().isBlank())
                line.append(' ').append(theme.styled("statusText", tool.detail()));
            line.append(dot).append(theme.styled("commandMeta", "interrupted"));
            long elapsed = System.currentTimeMillis() - tool.startedAtMs();
            if (elapsed >= 1000)
                line.append(dot).append(theme.styled("commandMeta", Util.formatDuration(elapsed)));
            appendPlain(List.of(line.toString()));
        }
        redrawPrompt(); // release the live tool tail row in the same breath
    }

    /**
     * How long something must run before the tail offers a key to press. It appears exactly when the user starts
     * wondering, never for a quick command. Not "background" specifically: it gates the interrupt hint too.
     */
    private static final long HINT_AFTER_MS = 3000;

    /** Constant per process, so the OS-notation rewrite does not run on every repaint. */
    private static final String BACKGROUND_HINT = Keys.label(" \u00b7 Ctrl+B twice to background");
    private static final String INTERRUPT_HINT = Keys.label(" \u00b7 Esc twice to interrupt");

    /** The user's own {@code !} command while it runs; the live tail names it the way it names a tool call. */
    private record RunningShell(String command, long startedAtMs) {
    }

    private volatile RunningShell runningShell;

    /**
     * The width a block is laid out for. JLine reports 0 when it cannot size the terminal, and a block wrapped to 0
     * draws one character per row, so every caller floors it -- in one place rather than four.
     */
    private int blockWidth() {
        return Math.max(20, terminal.getWidth());
    }

    /**
     * The live line above the prompt naming what is running: a tool call, or the user's own {@code !} command.
     * Committed results replace it in place, so the header appears to solidify. Null when nothing is in flight.
     *
     * <p>
     * Whichever started most recently wins, so a tool call fired while a {@code !} command is still running does not
     * leave the tail describing the older of the two.
     */
    private String runningToolTail() {
        RunningShell shell = runningShell;
        ToolInProgress current = newestToolInProgress();
        if (shell != null && (current == null || shell.startedAtMs() >= current.startedAtMs())) {
            long elapsed = System.currentTimeMillis() - shell.startedAtMs();
            return tail("! " + shell.command(), elapsed, INTERRUPT_HINT);
        }
        if (current == null)
            return null;
        // The tool's own mark, not a fixed one: this is the most-watched line in the interface during a turn, and a
        // hardcoded ● said every running tool -- a read, a search, a shell command -- had written to disk.
        String mark = session.tool(current.name()).map(Tool::glyph).orElse(ToolGlyphs.AGENT);
        var line = new StringBuilder(ToolResultLines.glyphPrefix(mark, current.name()))
                                        .append(ToolResultLines.label(current.name()));
        if (!current.detail().isBlank())
            line.append(' ').append(current.detail());
        long elapsed = System.currentTimeMillis() - current.startedAtMs();
        // The registry lookup stays behind the elapsed guard: session.tool() takes a lock, and this runs on every
        // repaint of every call, ~8 times a second, discarding the answer for the first three seconds of each.
        String hint = elapsed >= HINT_AFTER_MS && detachableTool(current.name()) != null ? BACKGROUND_HINT : "";
        return tail(line.toString(), elapsed, hint);
    }

    /**
     * A live tail line: what is running, how long it has been going, and -- once it is slow enough to wonder about --
     * what to press. No spinner: the status bar one row down is already animating, and two of them running at different
     * phases read as two separate things working.
     */
    private String tail(String what, long elapsedMs, String hint) {
        String elapsed = elapsedMs >= 1000 ? "  " + Util.formatDuration(elapsedMs) : "";
        return theme.styled("commandMeta", what + elapsed + (elapsedMs >= HINT_AFTER_MS ? hint : ""));
    }

    private ToolInProgress newestToolInProgress() {
        ToolInProgress current = null;
        for (var tool : toolsInProgress.values())
            if (current == null || tool.startedAtMs() > current.startedAtMs())
                current = tool;
        return current;
    }

    private Tool.Detachable detachableTool(String name) {
        if (jobRegistry == null)
            return null;
        return session.tool(name).filter(t -> t instanceof Tool.Detachable).map(t -> (Tool.Detachable) t).orElse(null);
    }

    /**
     * Ctrl+B pressed twice moves the running command to the background: the pending tool call completes with a
     * synthetic result carrying the new job id, the process is adopted by the job registry, and the turn resumes
     * immediately. A single press only arms the gate (and explains itself); with nothing detachable running the key is
     * inert.
     */
    private void handleCtrlB() {
        var current = newestToolInProgress();
        var detachable = current != null ? detachableTool(current.name()) : null;
        if (detachable == null || !activity.isBusy()) {
            backgroundGate.disarm();
            return;
        }
        if (!backgroundGate.tryFire()) {
            setTransientStatus("Press Ctrl+B again to move " + current.name() + " to the background", 2000);
            return;
        }
        backgroundGate.disarm();
        backgroundCurrentTool(current, detachable);
    }

    /** The /bg command: same effect as ctrl+b ctrl+b, without the chord (ctrl+b is the tmux prefix). */
    void backgroundRunningTool() {
        var current = newestToolInProgress();
        var detachable = current != null ? detachableTool(current.name()) : null;
        if (detachable == null || !activity.isBusy()) {
            appendPlain(List.of(theme.styled("statusText", "nothing to background. No detachable tool is running")));
            return;
        }
        backgroundCurrentTool(current, detachable);
    }

    private void backgroundCurrentTool(ToolInProgress current, Tool.Detachable detachable) {
        var handoff = detachable.detach();
        if (handoff.isEmpty()) {
            setTransientStatus("Nothing to background (the command just finished)", 2000);
            return;
        }
        var detached = handoff.get();
        var adopted = jobRegistry.adopt(detached, current.detail().isBlank() ? current.name() : current.detail());
        detached.complete().accept(JobRegistry.backgroundedResult(adopted.id(), adopted.outputSoFar()));
        setTransientStatus("Moved to background as " + adopted.id(), 2500);
    }

    /**
     * A custom message: {@code label: text} on one line, or the label on its own line above a multi-line body.
     *
     * <p>
     * It was always one concatenated string, so a body of several rows got the label on the first row only and the rest
     * arrived bare -- {@code /jobs} listing two jobs read as though the first were labelled and the second orphaned.
     * Rows of one kind should look alike, and the extension-rendered branch beside this one already put the label on
     * its own line.
     */
    private List<String> customMessageLines(Message.Custom custom) {
        String content = custom.content() == null ? "" : custom.content();
        var body = content.lines().toList();
        if (body.size() <= 1)
            return List.of(theme.styled("customMessageLabel", custom.customType() + ": ")
                                            + theme.styled("customMessageText", content));
        var lines = new ArrayList<String>();
        lines.add(theme.styled("customMessageLabel", custom.customType()));
        for (String line : body)
            lines.add(theme.styled("customMessageText", line));
        return lines;
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
        notifyExtensionsOfException(t);
    }

    /**
     * Forward an exception to extensions without rendering it, used for turn failures, which already surface to the
     * user via the {@link AgentEvent.Error} event, so calling {@link #dispatchException} there would double-print it.
     */
    private void notifyExtensionsOfException(Throwable t) {
        if (session != null && session.extensionRegistry() != null) {
            session.extensionRegistry().dispatchException(t);
        }
    }

    public void appendPlain(List<String> lines) {
        terminalUi.appendLines(lines.stream().map(StyledLine::of).toList());
    }

    private List<String> promptEcho(String text) {
        var out = new ArrayList<String>();
        out.add(""); // breathing room: set each turn off from the previous answer instead of butting them together
        // Wrap to the railed width ourselves before adding the rail, the same rule tool-result bodies follow: let the
        // terminal wrap instead and the continuation rows dangle bare at column zero, breaking the block open so a
        // long prompt stops looking like one thing the user said.
        int width = Math.max(20, terminal.getWidth());
        String rail = theme.styled("promptEcho", "│ ");
        boolean first = true;
        for (String logical : text.split("\\R", -1)) {
            out.addAll(AnsiWidth.wrapPrefixed(logical, width, theme.styled("promptEcho", first ? "▸ " : "│ "),
                                            row -> rail + row));
            first = false;
        }
        return out;
    }

    // Keys.label here is the single chokepoint for OS-native shortcut notation (⌃⌥⇧ on macOS): every status string
    // flows through set/setTransient, so callers never have to remember to wrap "Ctrl+…" themselves. It's a no-op on
    // strings without modifier tokens, and idempotent, so double-labelling is harmless.
    private void setStatusOverride(String status) {
        statusOverride.set(Keys.label(status));
    }

    private void setTransientStatus(String status, long durationMs) {
        statusOverride.setTransient(Keys.label(status), durationMs);
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
        // scheduleAtFixedRate silently cancels the task forever if it ever throws; a single transient redraw error
        // would then freeze the working spinner for the rest of the session (the prompt shows a frozen "●●●" while the
        // turn keeps running). Swallow everything so the next tick still fires.
        try {
            // While suspended (editor/shell handed the terminal to a child), don't advance or repaint; the
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
     * the key was consumed, redrawing afterwards. Modals don't touch {@code busy}; that reflects the agent turn, so the
     * working spinner shows only while the model is actually running, not while a viewer/picker is open.
     */
    private boolean routeModalKey(BooleanSupplier active, Predicate<Key> handleKey, Key key) {
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
        // region. Otherwise repaint just the tail row in place, no whole-region erase, so streaming never strobes.
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
        int others = promptLayout.measuredHeightExcluding(LogViewerRegion.class, width, theme, snapshot);
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
        return ModelRegistry.resolveContextWindow(provider, model);
    }

    static final class PendingRegion implements PromptRegion.Custom {
        @Override
        public Body compose(int width, AnsiTheme theme, PromptSnapshot ctx) {
            var rows = new ArrayList<String>(ctx.pendingMessages().size());
            for (String msg : ctx.pendingMessages())
                rows.add(theme.styled("statusWarn", AnsiWidth.truncateVisual("queued  " + msg, width)));
            return Body.of(rows);
        }
    }
}
