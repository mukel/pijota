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
import ai.pijota.config.OAuthLogin;
import ai.pijota.config.OAuthTokenStore;
import ai.pijota.config.ProviderCatalog;
import ai.pijota.messages.Message;
import ai.pijota.modes.SlashCommand;
import ai.pijota.theme.AnsiTheme;
import ai.pijota.tools.SlashCommandTool;
import ai.pijota.tools.Tool;
import ai.pijota.tools.WhoamiTool;
import ai.pijota.util.ErrorUtil;
import ai.pijota.util.ProcessRunner;
import ai.pijota.util.SelfRespawn;
import ai.pijota.util.Util;
import static ai.pijota.util.Util.fmtCount;
import static ai.pijota.util.Util.firstLine;
import static ai.pijota.util.Util.deltaPrefix;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class AppendOnlyTui implements AutoCloseable, UiContext {
    private static final String[] WORKING_FRAMES = {"●  ●●  ", " ● ●●  ", "  ●●●  ", "  ●● ● ", "  ●●  ●", "  ●● ● ",
            "  ●●●  ", " ● ●●  "};

    private AnsiTheme theme;
    private String provider;
    private String model;
    private String cwd;
    private String rawCwd;
    private final SettingsManager settings;
    private final PijotaSession session;
    private FileReferenceResolver fileRefs;
    private final List<SkillLoader.Skill> skills;
    private long contextWindow;
    private StreamingMarkdownFormatter markdown;
    private StreamingMarkdownFormatter thinkingMarkdown;
    private AnsiTheme thinkingTheme;
    private final Terminal terminal;
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
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicLong busyGeneration = new AtomicLong(0);
    private volatile String statusOverride;
    private volatile long statusOverrideExpiresAt;
    private final DialogHandler dialogs;

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
        this.markdown.setTheme(theme);
        this.thinkingMarkdown.setTheme(thinkingTheme);
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

    private volatile String busyPhase;
    private final AtomicInteger queuedFollowUps = new AtomicInteger(0);
    private final DoublePressGate interruptGate = new DoublePressGate(INTERRUPT_WINDOW_MS);
    private final DoublePressGate exitGate = new DoublePressGate(EXIT_WINDOW_MS);
    private ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
    private volatile boolean thinkingHeaderShown;
    private volatile boolean thinkingFooterShown;
    private volatile long thinkingStartedAt;
    private String sessionName;
    private Attributes originalAttributes;
    private final Terminal.SignalHandler originalIntHandler;
    private final Terminal.SignalHandler originalWinchHandler;
    private volatile String lastContent = "";
    private volatile String lastThinking = "";
    private volatile boolean compactionStreamRendered;
    private volatile boolean contentStarted;
    private final LineAccumulator contentLines = new LineAccumulator();
    private final LineAccumulator thinkingLines = new LineAccumulator();
    private final SlashCommandRegistry commands;
    private final TuiCommandController commandController;
    private final Map<String, SlashCommand> extensionCommands;
    private final Map<KeyChord, ExtensionKeybinding> extensionKeybindings;
    private final Map<String, Supplier<String>> toolArgsById = new ConcurrentHashMap<>();
    private final TuiSelectors selectors;
    private boolean loginIsLogout;
    private volatile boolean showSessionPickerOnStart;
    private final AtomicInteger workingFrame = new AtomicInteger();
    private final List<String> pendingFollowUps = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final MessageBrowser messageBrowser;
    private PendingEditManager pendingEdit;
    private final Supplier<List<String>> extensionLinesSupplier;
    private final ThemeManager themeMgr = new ThemeManager(this);
    private final StreamProcessor streamProc = new StreamProcessor();
    private final TranscendenceOrchestrator transcendence;
    private final ShellCommandRunner shellCommands;
    private final SessionCommands sessionCommands;
    private final ModelCommandHandler modelCommands;

    public void requestSessionPicker() {
        showSessionPickerOnStart = true;
    }
    private static final long INTERRUPT_WINDOW_MS = 1200;
    private static final long EXIT_WINDOW_MS = 800;
    private static final long TOKEN_STATUS_CACHE_MS = 1000;
    private volatile long cachedContextTokens = -1;
    private volatile long cachedContextTokensAt;
    private volatile int cachedContextMessageCount = -1;

    public AppendOnlyTui(PijotaSession session, AnsiTheme theme, String provider, String model,
                                    List<SlashCommand> extensionCommands, SettingsManager settings,
                                    List<SkillLoader.Skill> skills, Supplier<List<String>> extensionLinesSupplier,
                                    List<ExtensionKeybinding> extensionKeybindings) throws IOException {
        this.commands = new SlashCommandRegistry(extensionCommands);
        this.promptEditor = new PromptEditor(commands);
        this.messageBrowser = new MessageBrowser(promptEditor, session::userMessages);
        this.session = session;
        this.theme = theme;
        this.dialogs = new DialogHandler(new DialogHandler.Host() {
            @Override
            public void appendPlain(List<String> lines) {
                AppendOnlyTui.this.appendPlain(lines);
            }

            @Override
            public String styled(String slot, String text) {
                return AppendOnlyTui.this.theme.styled(slot, text);
            }

            @Override
            public void setStatus(String status) {
                setStatusOverride(status);
            }

            @Override
            public void clearStatus() {
                clearStatusOverride();
            }

            @Override
            public void redrawPrompt() {
                AppendOnlyTui.this.redrawPrompt();
            }
        });
        this.shellCommands = new ShellCommandRunner(new ShellCommandRunner.Host() {
            @Override
            public List<String> promptEcho(String text) {
                return AppendOnlyTui.this.promptEcho(text);
            }

            @Override
            public void appendPlain(List<String> lines) {
                AppendOnlyTui.this.appendPlain(lines);
            }

            @Override
            public String styled(String slot, String text) {
                return AppendOnlyTui.this.theme.styled(slot, text);
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
            public void redrawPrompt() {
                AppendOnlyTui.this.redrawPrompt();
            }

            @Override
            public void fireUserBash(String command, boolean noContext) {
                session.fireUserBash(command, noContext);
            }
        });
        this.sessionCommands = new SessionCommands(new SessionCommands.Host() {
            @Override
            public PijotaSession session() {
                return session;
            }

            @Override
            public String styled(String slot, String text) {
                return theme.styled(slot, text);
            }

            @Override
            public void appendPlain(List<String> lines) {
                AppendOnlyTui.this.appendPlain(lines);
            }

            @Override
            public boolean busy() {
                return busy.get();
            }

            @Override
            public String sessionName() {
                return AppendOnlyTui.this.sessionName;
            }

            @Override
            public void setSessionName(String name) {
                sessionName = name;
            }

            @Override
            public String provider() {
                return AppendOnlyTui.this.provider;
            }

            @Override
            public String model() {
                return AppendOnlyTui.this.model;
            }

            @Override
            public String cwd() {
                return AppendOnlyTui.this.cwd;
            }

            @Override
            public ThinkingLevel thinkingLevel() {
                return AppendOnlyTui.this.thinkingLevel;
            }

            @Override
            public SettingsManager settings() {
                return AppendOnlyTui.this.settings;
            }

            @Override
            public String tokenStatus() {
                return AppendOnlyTui.this.tokenStatus();
            }

            @Override
            public void refreshSessionName() {
                AppendOnlyTui.this.refreshSessionName();
            }
        });
        this.modelCommands = new ModelCommandHandler(new ModelCommandHandler.Host() {
            @Override
            public boolean busy() {
                return busy.get();
            }

            @Override
            public String provider() {
                return AppendOnlyTui.this.provider;
            }

            @Override
            public String model() {
                return AppendOnlyTui.this.model;
            }

            @Override
            public void appendPlain(List<String> lines) {
                AppendOnlyTui.this.appendPlain(lines);
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
            public void setStatus(String status) {
                setStatusOverride(status);
            }

            @Override
            public void switchModel(ModelInfo chosen) {
                AppendOnlyTui.this.switchToModel(chosen);
            }

            @Override
            public TuiSelectors selectors() {
                return selectors;
            }

            @Override
            public PromptEditor promptEditor() {
                return promptEditor;
            }

            @Override
            public PijotaSession session() {
                return session;
            }

            @Override
            public SettingsManager settings() {
                return settings;
            }
        });
        this.provider = provider;
        this.model = model;
        this.settings = settings;
        this.skills = skills;
        this.extensionLinesSupplier = extensionLinesSupplier != null ? extensionLinesSupplier : List::of;
        this.contextWindow = resolveContextWindow(provider, model);
        this.thinkingTheme = theme.thinking();
        this.markdown = new StreamingMarkdownFormatter(theme);
        this.thinkingMarkdown = new StreamingMarkdownFormatter(thinkingTheme, "thinkingText", true);
        this.terminal = TerminalBuilder.builder().dumb(false).jna(false).jansi(false).build();
        this.originalIntHandler = this.terminal.handle(Terminal.Signal.INT, _ -> handleCtrlC());
        this.originalWinchHandler = this.terminal.handle(Terminal.Signal.WINCH, _ -> handleResize());
        this.transcendence = new TranscendenceOrchestrator(new TranscendenceOrchestrator.Host() {
            @Override
            public boolean busy() {
                return busy.get();
            }

            @Override
            public void appendPlain(List<String> lines) {
                AppendOnlyTui.this.appendPlain(lines);
            }

            @Override
            public List<String> promptEcho(String text) {
                return AppendOnlyTui.this.promptEcho(text);
            }

            @Override
            public String styled(String slot, String text) {
                return theme.styled(slot, text);
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
            public void redrawPrompt() {
                AppendOnlyTui.this.redrawPrompt();
            }

            @Override
            public void resetStreamState() {
                AppendOnlyTui.this.resetStreamState();
            }

            @Override
            public void showSessionWarning() {
                AppendOnlyTui.this.showSessionWarning();
            }

            @Override
            public void suspendTui() {
                AppendOnlyTui.this.suspendTui();
            }

            @Override
            public void resumeTui() {
                AppendOnlyTui.this.resumeTui();
            }

            @Override
            public void flushTerminal() {
                terminal.writer().flush();
            }

            @Override
            public void submitAgent(Runnable task) {
                agentExecutor.submit(task);
            }

            @Override
            public CompletableFuture<List<Message>> promptSession(String prompt) {
                return session.prompt(prompt);
            }

            @Override
            public Path sessionFile() {
                return session.sessionFile();
            }

            @Override
            public void setStatusOverride(String status) {
                setStatusOverride(status);
            }
        });
        this.selectors = new TuiSelectors(this::confirmModelSelection, this::confirmSessionSelection,
                                        this::confirmThemeSelection, this::confirmSettingsSelection, () -> {
                                            if (loginIsLogout)
                                                confirmLogoutSelection();
                                            else
                                                confirmLoginSelection();
                                        }, this::confirmForkSelection);
        var regions = new ArrayList<PromptRegion>();
        regions.add(new PromptRegion.Editor());
        regions.add(new PendingRegion());
        regions.addAll(selectors.regions());
        regions.add(new PromptRegion.Completions());
        regions.add(new PromptRegion.StatusBar());
        var promptLayout = new PromptLayout(regions);
        this.terminalUi = new TerminalCoordinator(terminal, theme, promptLayout);
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
            terminalUi.start(decorate(promptEditor.snapshot()));
            animator.scheduleAtFixedRate(this::tickWorkingAnimation, 125, 125, TimeUnit.MILLISECONDS);
            inputThread = Thread.ofPlatform().name("pijota-tui-input").start(this::readInputLoop);
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
        lines.add(theme.styled("bannerName", "pijota " + version)
                                        + theme.styled("bannerMeta", "  " + compactModelName(provider, model)));
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

    private static String compactSessionDetail(String detail) {
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
                    if (key == Key.Special.OPEN_EDITOR || key == Key.Special.CTRL_G) {
                        openPromptInEditor();
                        continue;
                    }
                    if (key == Key.Special.SHIFT_TAB) {
                        thinkingLevel = thinkingLevel.next();
                        session.setThinkingLevel(thinkingLevel);
                        settings.setThinkingLevel(thinkingLevel.name());
                        appendPlain(List.of(theme.styled("statusText", "thinking " + thinkingLevel.label())));
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
                    if (key == Key.Special.CTRL_L) {
                        showModelSelector();
                        continue;
                    }
                    if (key == Key.Special.CTRL_P) {
                        cycleModel();
                        continue;
                    }
                    if (key == Key.Special.CTRL_SHIFT_P) {
                        cycleModelBackward();
                        continue;
                    }
                    if (selectors.handle(key, this::redrawPrompt))
                        continue;
                    if (promptEditor.hasCompletions()) {
                        if (key == Key.Special.UP) {
                            promptEditor.selectPrev();
                            redrawPrompt();
                            continue;
                        }
                        if (key == Key.Special.DOWN) {
                            promptEditor.selectNext();
                            redrawPrompt();
                            continue;
                        }
                    }
                    if (key == Key.Special.UP && (promptEditor.isEmpty() || promptEditor.isInHistory())) {
                        if (!promptEditor.historyPrev() && !busy.get()) {
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
                    if (messageBrowser.isActive()) {
                        if (key == Key.Special.UP) {
                            messageBrowser.prev();
                            redrawPrompt();
                            continue;
                        }
                        if (key == Key.Special.DOWN) {
                            messageBrowser.next();
                            redrawPrompt();
                            continue;
                        }
                        if (key == Key.Special.ENTER) {
                            messageBrowser.exit();
                            redrawPrompt();
                            continue;
                        }
                        if (key == Key.Special.ESC) {
                            messageBrowser.cancel();
                            redrawPrompt();
                            continue;
                        }
                        messageBrowser.exit();
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
                    clearStatusOverride();
                    redrawPrompt();
                }
            } catch (Exception ignored) {
                running.set(false);
                break;
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
            setStatusOverride("Keybinding error: " + ErrorUtil.message(e));
            redrawPrompt();
        }
        return true;
    }

    private static boolean isReservedKey(Key key) {
        return key == Key.Special.ESC || key == Key.Special.DOUBLE_ESC || key == Key.Special.CTRL_C
                                        || key == Key.Special.ENTER || key == Key.Special.NEWLINE
                                        || key == Key.Special.BACKSPACE || key == Key.Special.TAB
                                        || key == Key.Special.PASTE_START;
    }

    private static KeyChord toKeyChord(Key key) {
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

    private void handleEsc(boolean forceInterrupt) {
        if (dialogs.cancelQuestionIfIdle(busy.get()))
            return;
        if (!busy.get())
            return;
        if (forceInterrupt || interruptGate.tryFire()) {
            interruptGate.disarm();
            setStatusOverride("Interrupted");
            session.abort();
            flushStreamLines();
            resetStreamState();
            shellCommands.interruptProcess();
        } else {
            setTransientStatus("Press Esc again to interrupt", 2500);
        }
        redrawPrompt();
    }

    private void handleCtrlC() {
        if (exitGate.tryFire()) {
            running.set(false);
            close();
            return;
        }
        promptEditor.setText("");
        setTransientStatus("Press Ctrl+C again to exit", 2500);
        redrawPrompt();
    }

    private void suspendTui() {
        terminal.writer().print("\033[?25h\033[0m\r\n");
        terminal.writer().flush();
        if (originalAttributes != null)
            terminal.setAttributes(originalAttributes);
    }

    private void resumeTui() {
        try {
            originalAttributes = terminal.enterRawMode();
        } catch (Exception ignored) {}
    }

    private void openPromptInEditor() {
        if (busy.get()) {
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
            int code = ProcessRunner.command("/bin/sh", "-lc", editor + " " + Util.shellQuote(file.toString()))
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
        if (busy.get() && session.isBusy()) {
            session.followUp(payload);
            pendingFollowUps.add(payload);
            int queued = queuedFollowUps.incrementAndGet();
            appendPlain(List.of(theme.styled("statusText", "queued follow-up (" + queued + ")")));
            redrawPrompt();
            return;
        }
        queuedFollowUps.set(0);
        pendingFollowUps.clear();
        exitGate.disarm();
        long gen = beginBusy("waiting for model");
        redrawPrompt();
        agentExecutor.submit(() -> {
            try {
                resetStreamState();
                session.prompt(payload).join();
            } catch (CompletionException e) {
                setStatusOverride("Error: " + ErrorUtil.message(e.getCause()));
            } finally {
                showSessionWarning();
                endBusy(gen);
                redrawPrompt();
            }
        });
    }

    private long beginBusy(String phase) {
        busy.set(true);
        busyPhase = phase;
        interruptGate.disarm();
        clearStatusOverride();
        return busyGeneration.incrementAndGet();
    }

    private void endBusy(long generation) {
        if (!busyGeneration.compareAndSet(generation, 0))
            return;
        busy.set(false);
        interruptGate.disarm();
        clearStatusOverride();
    }

    boolean runExtensionCommand(SlashCommand extCmd, String input, String cmd) {
        if (extCmd == null || extCmd.handler() == null)
            return false;
        suspendTui();
        extCmd.handler().accept(TuiCommandController.commandArgs(input, cmd));
        resumeTui();
        promptEditor.setText("");
        redrawPrompt();
        return true;
    }

    private void handleResize() {
        markdown.setWidth(terminal.getWidth() - 4);
        thinkingMarkdown.setWidth(terminal.getWidth() - 4);
        redrawPrompt();
    }

    void showSkills() {
        if (skills.isEmpty()) {
            appendPlain(List.of(theme.styled("statusText", "no skills found")));
            return;
        }
        var lines = new ArrayList<String>();
        lines.add(theme.styled("toolLabel", "skills (" + skills.size() + ")"));
        for (var s : skills) {
            String desc = s.description() != null && !s.description().isBlank() ? "  " + s.description() : "";
            lines.add(theme.styled("statusText", "  " + s.name()) + theme.styled("commandMeta", desc));
        }
        lines.add(theme.styled("statusText", "usage: /skill:<name> [instructions]"));
        appendPlain(lines);
    }

    void handleSkillCommand(String input) {
        String rest = input.substring("/skill:".length());
        String name = rest;
        String instructions = "";
        int space = rest.indexOf(' ');
        if (space > 0) {
            name = rest.substring(0, space);
            instructions = rest.substring(space + 1).trim();
        }
        var skill = SkillLoader.findByName(skills, name);
        if (skill == null) {
            appendPlain(List.of(theme.styled("toolError", "skill not found: " + name)));
            return;
        }
        String expanded = "# Skill: " + skill.name() + "\n\n" + skill.content()
                                        + (instructions.isEmpty() ? "" : "\n\n# Instructions\n\n" + instructions);
        appendPlain(promptEcho(input));
        long gen = beginBusy("waiting for model");
        redrawPrompt();
        agentExecutor.submit(() -> {
            try {
                session.prompt(expanded).join();
            } finally {
                showSessionWarning();
                endBusy(gen);
            }
        });
    }

    private void showSessionWarning() {
        session.consumeSessionWarning().ifPresent(warning -> appendPlain(List.of(theme.styled("toolError", warning))));
    }

    void showSettingsSelector() {
        if (settings == null) {
            appendPlain(List.of(theme.styled("toolError", "settings unavailable")));
            return;
        }
        selectors.show(TuiSelectors.Slot.SETTINGS, settingsItems());
        promptEditor.setText("");
        redrawPrompt();
        return;
    }

    private List<SettingsItem> settingsItems() {
        var cfg = settings.config();
        boolean context = settings.contextFilesEnabled();
        boolean compact = settings.compactionEnabled();
        boolean shorten = settings.shortenPathsEnabled();
        return List.of(new SettingsItem("auto_compact", "Auto-compact", compact ? "on" : "off", compact),
                                        new SettingsItem("context_files", "Context files", context
                                                                        ? "on  next session"
                                                                        : "off  next session", context),
                                        new SettingsItem("shorten_paths", "Shorten paths", shorten ? "on" : "off",
                                                                        shorten),
                                        new SettingsItem("thinking_level", "Thinking", thinkingLevel.label(), true),
                                        new SettingsItem("hide_thinking", "Hide thinking",
                                                                        cfg.hideThinking() ? "on" : "off",
                                                                        cfg.hideThinking()),
                                        new SettingsItem("quiet_startup", "Quiet startup", cfg.quietStartup()
                                                                        ? "on  next start"
                                                                        : "off  next start", cfg.quietStartup()));
    }

    private void confirmSettingsSelection() {
        var chosen = selectors.<SettingsItem>selected(TuiSelectors.Slot.SETTINGS).orElse(null);
        if (chosen == null)
            return;
        switch (chosen.id()) {
            case "auto_compact" -> settings.setCompactionEnabled(!settings.compactionEnabled());
            case "context_files" -> settings.setContextFilesEnabled(!settings.contextFilesEnabled());
            case "thinking_level" -> {
                thinkingLevel = thinkingLevel.next();
                session.setThinkingLevel(thinkingLevel);
                settings.setThinkingLevel(thinkingLevel.name());
            }
            case "hide_thinking" -> settings.setHideThinking(!settings.config().hideThinking());
            case "quiet_startup" -> settings.setQuietStartup(!settings.config().quietStartup());
            case "shorten_paths" -> settings.setShortenPathsEnabled(!settings.shortenPathsEnabled());
            default -> {}
        }
        selectors.refresh(TuiSelectors.Slot.SETTINGS, settingsItems());
        if (chosen.id().equals("auto_compact")) {
            session.setAutoCompactionEnabled(settings.compactionEnabled());
        }
        setTransientStatus(chosen.id().equals("context_files") || chosen.id().equals("quiet_startup")
                                        ? chosen.titleText() + " updated for next start/session"
                                        : chosen.titleText() + " updated", 2500);
    }

    void showLoginSelector() {
        var items = new ArrayList<LoginProviderItem>();
        for (var p : ProviderCatalog.providers()) {
            if (p.oauth() != null) {
                items.add(new LoginProviderItem(p.id(), p.displayName(), "OAuth", false));
            }
        }
        if (items.isEmpty()) {
            appendPlain(List.of(theme.styled("toolError", "no providers support OAuth login")));
            return;
        }
        loginIsLogout = false;
        selectors.show(TuiSelectors.Slot.LOGIN, items);
        promptEditor.setText("");
        redrawPrompt();
    }

    private void confirmLoginSelection() {
        var chosen = selectors.<LoginProviderItem>selected(TuiSelectors.Slot.LOGIN).orElse(null);
        if (chosen == null)
            return;
        selectors.deactivate(TuiSelectors.Slot.LOGIN);
        promptEditor.setText("");
        redrawPrompt();
        appendPlain(List.of(theme.styled("statusText", "Starting OAuth login for " + chosen.titleText() + "...")));

        agentExecutor.submit(() -> {
            try {
                var oauth = ProviderCatalog.oauth(chosen.id());
                if (oauth.isEmpty()) {
                    appendPlain(List.of(theme.styled("toolError", chosen.titleText() + " has no OAuth config")));
                    return;
                }
                var o = oauth.get();

                suspendTui();

                OAuthLogin.TokenResult result;
                if (o.oauthTokenEndpoint() != null && !o.oauthTokenEndpoint().isBlank()) {
                    result = OAuthLogin.loginOpenAI(o.deviceEndpoint(), o.tokenEndpoint(), o.oauthTokenEndpoint(),
                                                    o.clientId());
                } else {
                    result = OAuthLogin.login(o.deviceEndpoint(), o.tokenEndpoint(), o.clientId(), o.scope());
                }

                resumeTui();

                var oauthCfg = oauth.get();
                String tokenUrl = oauthCfg.oauthTokenEndpoint() != null && !oauthCfg.oauthTokenEndpoint().isBlank()
                                                ? oauthCfg.oauthTokenEndpoint()
                                                : oauthCfg.tokenEndpoint();
                var store = new OAuthTokenStore();
                store.save(chosen.id(), result.accessToken(), result.refreshToken(), result.expiresAt(),
                                                result.accountId(), tokenUrl, oauthCfg.clientId());
                String defaultModel = ModelRegistry.defaultModelForProvider(chosen.id());
                if (defaultModel != null) {
                    settings.setDefaultModelForProvider(chosen.id(), defaultModel);
                }
                appendPlain(List.of(theme.styled("statusText", System.lineSeparator() + "Logged in to "
                                                + chosen.titleText() + "  model: "
                                                + (defaultModel != null ? defaultModel : ""))));
                redrawPrompt();
            } catch (Exception e) {
                if (e instanceof InterruptedException)
                    Thread.currentThread().interrupt();
                resumeTui();
                appendPlain(List.of(theme.styled("toolError",
                                                "Login failed: " + (e.getMessage() != null
                                                                                ? e.getMessage()
                                                                                : e.getClass().getSimpleName()))));
                redrawPrompt();
            }
        });
    }

    void showLogoutSelector() {
        var store = new ai.pijota.config.OAuthTokenStore();
        var items = new ArrayList<LoginProviderItem>();
        for (var p : ProviderCatalog.providers()) {
            if (p.oauth() != null) {
                String tok = store.getAccessToken(p.id());
                items.add(new LoginProviderItem(p.id(), p.displayName(), tok != null ? "logged in" : "not logged in",
                                                false));
            }
        }
        if (items.isEmpty()) {
            appendPlain(List.of(theme.styled("toolError", "no providers to log out")));
            return;
        }
        loginIsLogout = true;
        selectors.show(TuiSelectors.Slot.LOGIN, items);
        promptEditor.setText("");
        redrawPrompt();
    }

    private void confirmLogoutSelection() {
        var chosen = selectors.<LoginProviderItem>selected(TuiSelectors.Slot.LOGIN).orElse(null);
        if (chosen == null)
            return;
        selectors.deactivate(TuiSelectors.Slot.LOGIN);
        promptEditor.setText("");
        redrawPrompt();
        var store = new OAuthTokenStore();
        store.remove(chosen.id());
        try {
            settings.removeApiKey(chosen.id());
        } catch (IOException ignored) {}
        appendPlain(List.of(theme.styled("statusText", "Logged out of " + chosen.titleText())));
    }

    void showThemeSelector() {
        var items = themeMgr.discover();
        if (items.isEmpty()) {
            appendPlain(List.of(theme.styled("toolError", "no themes found")));
            return;
        }
        selectors.show(TuiSelectors.Slot.THEME, items);
        selectors.<ThemeItem>selectFirst(TuiSelectors.Slot.THEME, ThemeItem::current);
        promptEditor.setText("");
        redrawPrompt();
    }

    private void confirmThemeSelection() {
        var chosen = selectors.<ThemeItem>selected(TuiSelectors.Slot.THEME).orElse(null);
        if (chosen == null)
            return;
        if (chosen.path().isBlank())
            themeMgr.switchTo(chosen.name());
        else
            themeMgr.switchToPath(chosen.path());
        selectors.deactivate(TuiSelectors.Slot.THEME);
        promptEditor.setText("");
        redrawPrompt();
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

    private void confirmModelSelection() {
        modelCommands.confirmModelSelection();
    }

    void showSessionPicker(String query) {
        if (busy.get()) {
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

    private void cycleModelBackward() {
        modelCommands.cycleModel(-1);
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
        var tool = session.agentLoop().toolRegistry().get("whoami").orElse(null);
        if (tool instanceof WhoamiTool wt) {
            wt.setModelInfo(provider, model);
        }
    }

    void triggerCompaction(String instructions) {
        if (busy.get()) {
            appendPlain(List.of(theme.styled("toolError", "/compact unavailable while model is responding")));
            return;
        }
        appendPlain(List.of(theme.styled("toolLabel",
                                        "/compact" + (instructions.isBlank() ? "" : ": " + instructions))));
        long gen = beginBusy("compacting");
        resetStreamState();
        compactionStreamRendered = false;
        redrawPrompt();
        agentExecutor.submit(() -> {
            try {
                var result = session.compact(instructions);
                if (result == null || !result.compacted()) {
                    appendPlain(List.of(theme.styled("statusText", "nothing to compact yet")));
                }
            } catch (Exception e) {
                appendPlain(List.of(theme.styled("toolError", "compact failed: " + e.getMessage())));
            } finally {
                flushStreamLines();
                endBusy(gen);
                redrawPrompt();
            }
        });
    }

    void copyLastResponse() {
        sessionCommands.copyLastResponse();
    }

    void showHotkeys() {
        var lines = new ArrayList<String>();
        lines.add(theme.styled("heading", "keyboard shortcuts"));
        lines.add("");

        // two-column layout
        String[][] keys = {{"Enter", "submit prompt"}, {"Ctrl+J", "newline"}, {"Tab", "autocomplete"},
                {"Shift+Tab", "toggle thinking"}, {"Up/Down", "history / navigate"},
                {"Esc", "interrupt (double-force)"}, {"Ctrl+C", "clear / double to exit"}, {"Ctrl+K", "delete to end"},
                {"Ctrl+U", "clear line"}, {"Ctrl+W", "delete word"}, {"Ctrl+A/E", "start/end of line"},
                {"Ctrl+L", "select model"}, {"Ctrl+P", "cycle model forward"}, {"Ctrl+Shift+P", "cycle model backward"},
                {"Ctrl+G", "open in editor"},};

        int keyWidth = 0;
        for (var k : keys)
            keyWidth = Math.max(keyWidth, AnsiWidth.visibleWidth(k[0]));
        keyWidth += 2;

        for (var k : keys) {
            lines.add(theme.styled("toolLabel", padRight(k[0], keyWidth)) + theme.styled("statusText", k[1]));
        }
        lines.add("");
        lines.add(theme.styled("statusText", "commands  /help /model /theme /compact /session /tree /fork /undo"));
        lines.add(theme.styled("statusText", "shell     !cmd adds context · !!cmd runs without context"));

        for (var binding : extensionKeybindings.values()) {
            lines.add(theme.styled("toolLabel", padRight(binding.key().toString(), keyWidth))
                                            + theme.styled("statusText", binding.description()));
        }
        appendPlain(lines);
    }

    private static String padRight(String s, int width) {
        int w = AnsiWidth.visibleWidth(s);
        return s + " ".repeat(Math.max(0, width - w));
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
        if (busy.get()) {
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
        if (busy.get()) {
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

    void handleTranscend(String args) {
        transcendence.handle(args);
    }

    void undoLastTurn() {
        if (busy.get()) {
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
                pendingFollowUps.clear();
                resetStreamState();
                thinkingHeaderShown = false;
                busyPhase = "waiting for model";
                redrawPrompt();
            }
            case AgentEvent.AssistantMessage(Message.Assistant msg, var usage) -> {
                if (usage == null)
                    busyPhase = null;
                String content = msg.content() == null ? "" : msg.content();
                String delta = deltaPrefix(content, lastContent);
                boolean customMessageRenderer = session.extensionRegistry().hasMessageRenderers();
                if (showThinking() && msg.thinking() != null && !msg.thinking().isBlank()) {
                    if (!thinkingHeaderShown) {
                        thinkingStartedAt = System.currentTimeMillis();
                        appendPlain(List.of(thinkingHeader()));
                        thinkingHeaderShown = true;
                    }
                    String thinkingDelta = deltaPrefix(msg.thinking(), lastThinking);
                    appendThinking(thinkingLines.accept(thinkingDelta));
                    lastThinking = msg.thinking();
                }
                if (customMessageRenderer) {
                    if (usage != null)
                        renderFinalAssistantMessage(msg, content, usage);
                    redrawPrompt();
                    return;
                }
                redrawPrompt();
                if (!delta.isEmpty()) {
                    if (!contentStarted) {
                        if (thinkingHeaderShown) {
                            closeThinkingBlock();
                        }
                        contentStarted = true;
                    }
                }
                appendContent(contentLines.accept(delta));
                lastContent = content;
                if (usage != null) {
                    closeThinkingBlock();
                    addUsage(usage);
                    flushStreamLines();
                    redrawPrompt();
                }
            }
            case AgentEvent.ToolStart(var name, var toolCallId, var args) -> {
                closeThinkingBlock();
                flushStreamLines();
                if (toolCallId != null && args != null)
                    toolArgsById.put(toolCallId, args);
                pendingEdit.onToolStart(name, args);
                busyPhase = "tool " + name;
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
                flushStreamLines();
                pendingEdit.onToolResult(isEditWrite(msg), msg.isError());
                String slot = msg.isError() ? "toolError" : "toolResult";
                var parts = new ArrayList<String>();
                String icon = toolIcon(msg.toolName(), msg.isError());
                parts.add(theme.styled(msg.isError() ? "toolError" : "toolLabel", icon));
                parts.add(theme.styled("statusText", msg.toolName()));
                Supplier<String> args = msg.toolCallId() != null ? toolArgsById.remove(msg.toolCallId()) : null;
                String detail = ToolRender.detail(msg.toolName(), args, rawCwd,
                                                settings != null && settings.shortenPathsEnabled());
                if (!detail.isBlank())
                    parts.add(theme.styled("statusText", detail));
                Tool.ToolResultView rendered = renderToolResultView(msg);
                String summary = rendered != null ? rendered.summary() : toolResultSummary(msg);
                if (!summary.isBlank())
                    parts.add(theme.styled(slot, summary));
                if ("bash".equals(msg.toolName())) {
                    Map<String, Object> details = msg.details() == null ? Map.of() : msg.details();
                    if (details.get("exitCode") instanceof Number ec) {
                        parts.add(theme.styled(ec.intValue() == 0 ? "statusText" : "toolError",
                                                        "exit " + ec.intValue()));
                    }
                }
                if (isEditWrite(msg) && pendingEdit.hasEditResult() && !pendingEdit.previewShown()) {
                    appendPlain(new DiffRenderer(theme).render(msg.toolName(), pendingEdit.filePath(),
                                                    pendingEdit.oldText(), pendingEdit.newText(), terminal.getWidth()));
                }
                var lines = new ArrayList<String>();
                lines.add(String.join(" ", parts));
                if (rendered != null && !rendered.body().isEmpty()) {
                    for (String line : rendered.body()) {
                        lines.add(renderToolBodyLine(msg.toolName(), line));
                    }
                }
                appendPlain(lines);
                pendingEdit.clear();
            }
            case AgentEvent.Compaction c -> {
                int remaining = c.compactedMessages().size();
                int removed = c.oldCount() - remaining;
                int estTokens = c.estimatedTokens();
                String reason = c.reason() != null && !c.reason().isBlank() ? " · " + c.reason() : "";
                if (!compactionStreamRendered && c.summary() != null && !c.summary().isBlank()) {
                    renderFinalCompactionSummary(c.summary());
                }
                appendPlain(List.of(theme.styled("statusText", "[compacted] " + c.oldCount() + " → " + remaining
                                                + " msgs" + " · ~" + fmtCount(estTokens) + " tokens" + reason)));
                compactionStreamRendered = false;
            }
            case AgentEvent.CompactionStream stream -> renderCompactionStream(stream.content(), stream.thinking());
            case AgentEvent.CompactionStreamEnd _ -> {
                flushStreamLines();
                resetStreamState();
            }
            case AgentEvent.CustomMessage(Message.Custom custom) -> {
                flushStreamLines();
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

    private void renderFinalAssistantMessage(Message.Assistant msg, String content, ai.pijota.messages.Usage usage) {
        var rendered = session.extensionRegistry().renderMessage(msg);
        if (rendered.isPresent()) {
            appendPlain(rendered.get());
        } else if (!content.isEmpty()) {
            appendContent(contentLines.accept(content));
        }
        addUsage(usage);
        flushStreamLines();
    }

    private void appendContent(List<String> lines) {
        if (lines.isEmpty())
            return;
        int width = terminal.getWidth() - 4;
        List<StyledLine> rendered;
        synchronized (this) {
            rendered = renderContent(lines, width);
        }
        terminalUi.appendLines(rendered);
    }

    private void renderCompactionStream(String content, String thinking) {
        String text = content == null ? "" : Util.stripSystemReminders(content);
        String delta = deltaPrefix(text, lastContent);
        if (!delta.isEmpty()) {
            if (!compactionStreamRendered) {
                appendPlain(List.of(theme.styled("statusText", "  ── compacting ──")));
            }
            appendContent(contentLines.accept(delta));
            lastContent = text;
            compactionStreamRendered = true;
        }
    }

    private void renderFinalCompactionSummary(String summary) {
        resetStreamState();
        appendPlain(List.of(theme.styled("statusText", "  ── compacted ──")));
        appendContent(contentLines.accept(summary));
        flushStreamLines();
    }

    private String renderToolBodyLine(String toolName, String line) {
        if ("todowrite".equals(toolName))
            return renderTodoLine(line);
        if (line.startsWith("╰→")) {
            return theme.styled("statusText", "╰→") + theme.styled("commandMeta", line.substring(2));
        }
        if (line.equals("┴") || line.equals("┬"))
            return theme.styled("statusText", line);
        String bodySlot = line.startsWith("⋯") ? "commandMeta" : "statusText";
        return theme.styled(bodySlot, line);
    }

    private String renderTodoLine(String line) {
        String slot = null;
        if (line.startsWith("│ [✓]"))
            slot = "diffAdd";
        else if (line.startsWith("│ [~]"))
            slot = "statusWarn";
        else if (line.startsWith("│ [−]"))
            slot = "toolError";
        if (slot == null)
            return theme.styled("statusText", line);
        return theme.styled("statusText", "│ ") + theme.styled(slot, line.substring(2));
    }

    private List<StyledLine> renderContent(List<String> lines, int width) {
        markdown.setWidth(width);
        var out = new ArrayList<StyledLine>();
        for (String line : lines) {
            appendFilteredAssistantLine(line, out);
        }
        return out;
    }

    private String toolResultSummary(Message.ToolResult msg) {
        if (msg.isError()) {
            String content = msg.content();
            if (content == null || content.isBlank())
                return "";
            return content.lines().limit(5).reduce("", (a, b) -> a + (a.isEmpty() ? "" : "\n") + b);
        }
        Map<String, Object> details = msg.details() == null ? Map.of() : msg.details();
        return switch (msg.toolName()) {
            case "grep" ->
                details.get("matchCount") != null ? details.get("matchCount") + " match(es)" : firstLine(msg.content());
            case "glob" -> details.get("resultCount") != null
                                            ? details.get("resultCount") + " result(s)"
                                            : firstLine(msg.content());
            case "read" ->
                details.get("totalLines") != null ? details.get("totalLines") + " line(s)" : firstLine(msg.content());
            case "task" -> details.get("resultCount") != null
                                            ? details.get("mode") + ": " + details.get("succeeded") + "/"
                                                                            + details.get("resultCount") + " ok"
                                            : firstLine(msg.content());
            default -> firstLine(msg.content());
        };
    }

    private Tool.ToolResultView renderToolResultView(Message.ToolResult msg) {
        return switch (msg.toolName()) {
            case "bash", "task", "todowrite", "read", "grep", "write", "edit" -> {
                var args = pendingEdit.toolArgs() != null
                                                ? Util.parseJsonArgs(pendingEdit.toolArgs().get())
                                                : Map.<String, Object>of();
                yield session.agentLoop().toolRegistry().get(msg.toolName()).map(tool -> tool.renderResult(args,
                                                new Tool.ToolResult(msg.content(), msg.isError(), msg.details() == null
                                                                                ? Map.of()
                                                                                : msg.details()),
                                                rawCwd)).orElse(null);
            }
            default -> null;
        };
    }

    private void appendThinking(List<String> lines) {
        if (lines.isEmpty())
            return;
        List<StyledLine> rendered;
        synchronized (this) {
            rendered = renderThinking(lines);
        }
        terminalUi.appendLines(rendered);
    }

    private List<StyledLine> renderThinking(List<String> lines) {
        int width = terminal.getWidth() - 4;
        thinkingMarkdown.setWidth(width);
        var out = new ArrayList<StyledLine>();
        for (String line : lines)
            out.addAll(thinkingMarkdown.format(line));
        return out;
    }

    private void closeThinkingBlock() {
        if (!thinkingHeaderShown || thinkingFooterShown)
            return;
        thinkingLines.flush().ifPresent(line -> appendThinking(List.of(line)));
        appendPlain(List.of(thinkingSummary(lastThinking, thinkingStartedAt), ""));
        thinkingFooterShown = true;
    }

    private String thinkingHeader() {
        return theme.styled("toolLabel", "› thinking") + theme.styled("dim", " · " + thinkingLevel.label());
    }

    private String thinkingSummary(String thinking, long startedAt) {
        int tokens = (int) Math.ceil((thinking == null ? 0 : thinking.length()) / 4.0);
        long elapsed = startedAt > 0 ? System.currentTimeMillis() - startedAt : 0;
        return theme.styled("thinkingText", "└ ") + theme.styled("thinkingText", "thought ")
                                        + theme.styled("thinkingText", fmtCount(tokens) + " tokens")
                                        + theme.styled("thinkingText", " · " + Util.formatDuration(elapsed));
    }

    private void flushStreamLines() {
        int width = terminal.getWidth() - 4;
        List<List<StyledLine>> batches = new ArrayList<>();
        synchronized (this) {
            if (showThinking())
                thinkingLines.flush().ifPresent(line -> batches.add(renderThinking(List.of(line))));
            contentLines.flush().ifPresent(line -> batches.add(renderContent(List.of(line), width)));
            markdown.setWidth(width);
            batches.add(markdown.flush());
            thinkingMarkdown.setWidth(width);
            batches.add(thinkingMarkdown.flush());
        }
        for (var batch : batches)
            terminalUi.appendLines(batch);
    }

    private void resetStreamState() {
        contentLines.clear();
        thinkingLines.clear();
        streamProc.reset();
        contentStarted = false;
        thinkingHeaderShown = false;
        thinkingFooterShown = false;
        thinkingStartedAt = 0;
        lastContent = "";
        lastThinking = "";
        markdown.reset();
        thinkingMarkdown.reset();
    }

    private void appendFilteredAssistantLine(String line, List<StyledLine> out) {
        String cleaned = streamProc.stripAndFilter(line);
        if (cleaned != null)
            out.addAll(markdown.format(cleaned));
    }

    void appendPlain(List<String> lines) {
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

    List<String> lines(String text, String slot) {
        return text.lines().map(line -> theme.styled(slot, line)).toList();
    }

    private PromptSnapshot decorate(PromptSnapshot snapshot) {
        String tokens = tokenStatus();
        String status = currentStatusOverride();
        String promptLine = dialogs.promptStatusLine();
        if (promptLine != null) {
            status = status != null ? status + "\n" + promptLine : promptLine;
        }
        String cwdLine = theme.styled("commandMeta", this.cwd);
        if (sessionName != null && !sessionName.isBlank()) {
            cwdLine += theme.styled("commandMeta", "  " + sessionName);
        }
        cwdLine += theme.styled("commandMeta", "  " + compactModelName(provider, model));
        if (thinkingLevel != ThinkingLevel.OFF) {
            cwdLine += theme.styled("commandMeta", "  " + thinkingLevel.label());
        }
        snapshot = snapshot.withCwd(cwdLine).withContextPct(tokenPct())
                                        .withPendingMessages(List.copyOf(pendingFollowUps))
                                        .withExtensionLines(mergedExtensionLines())
                                        .withPromptState(promptState(snapshot, status));
        if (!busy.get()) {
            return snapshot.withStatus(tokens).withWarning(status);
        }
        String frame = WORKING_FRAMES[Math.floorMod(workingFrame.get(), WORKING_FRAMES.length)];
        int queued = queuedFollowUps.get();
        String bar = tokens + "  " + frame;
        if (busyPhase != null && !busyPhase.isBlank())
            bar += " " + busyPhase;
        if (queued > 0)
            bar += "  queued " + queued;
        return snapshot.withStatus(bar).withWarning(status);
    }

    private static String compactModelName(String provider, String model) {
        if (provider == null || provider.isBlank())
            return model;
        if (model == null || model.isBlank())
            return provider;
        String shortModel = model;
        for (String prefix : List.of(provider + "-")) {
            if (shortModel.startsWith(prefix)) {
                shortModel = shortModel.substring(prefix.length());
                break;
            }
        }
        return provider + "/" + shortModel;
    }

    private PromptSnapshot.PromptState promptState(PromptSnapshot snapshot, String status) {
        if (busy.get())
            return PromptSnapshot.PromptState.BUSY;
        if (status != null && (status.startsWith("Error:") || "Interrupted".equals(status))) {
            return PromptSnapshot.PromptState.ERROR;
        }
        return insidePromptCodeBlock(snapshot.lines())
                                        ? PromptSnapshot.PromptState.CONTINUATION
                                        : PromptSnapshot.PromptState.IDLE;
    }

    private static boolean insidePromptCodeBlock(List<String> lines) {
        String marker = null;
        for (String line : lines) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("```")) {
                if (marker == null)
                    marker = "```";
                else if ("```".equals(marker))
                    marker = null;
            } else if (stripped.startsWith("~~~")) {
                if (marker == null)
                    marker = "~~~";
                else if ("~~~".equals(marker))
                    marker = null;
            }
        }
        return marker != null;
    }

    private List<String> mergedExtensionLines() {
        var raw = extensionLinesSupplier.get();
        raw = raw.stream().filter(AppendOnlyTui::showExtensionStatusLine).toList();
        if (raw.size() <= 1)
            return raw;
        var seen = new java.util.LinkedHashSet<>(raw);
        return seen.size() == raw.size() ? raw : List.copyOf(seen);
    }

    private static boolean showExtensionStatusLine(String line) {
        if (line == null)
            return false;
        String normalized = line.toLowerCase(java.util.Locale.ROOT);
        return !(normalized.contains("filtered") || normalized.contains("secured"));
    }

    private String currentStatusOverride() {
        if (statusOverride == null)
            return null;
        long expiresAt = statusOverrideExpiresAt;
        if (expiresAt > 0 && System.currentTimeMillis() >= expiresAt) {
            clearStatusOverride();
            return null;
        }
        return statusOverride;
    }

    private void setStatusOverride(String status) {
        statusOverride = status;
        statusOverrideExpiresAt = 0;
    }

    private void setTransientStatus(String status, long durationMs) {
        statusOverride = status;
        statusOverrideExpiresAt = System.currentTimeMillis() + Math.max(1, durationMs);
    }

    private void clearStatusOverride() {
        statusOverride = null;
        statusOverrideExpiresAt = 0;
    }

    private static boolean isEditWrite(Message.ToolResult msg) {
        return "edit".equals(msg.toolName()) || "write".equals(msg.toolName());
    }

    private static String toolIcon(String toolName, boolean error) {
        if (error)
            return "✖";
        return switch (toolName) {
            case "read" -> "📖";
            case "write", "edit" -> "✏";
            case "bash" -> "$";
            case "grep" -> "🔍";
            case "glob" -> "📁";
            case "ls" -> "📂";
            case "task" -> "⚙";
            case "question" -> "❓";
            case "websearch", "webfetch" -> "🌐";
            case "todowrite" -> "📋";
            default -> "●";
        };
    }

    private boolean showThinking() {
        return thinkingLevel != ThinkingLevel.OFF && (settings == null || !settings.config().hideThinking());
    }

    private void tickWorkingAnimation() {
        if (!running.get() || SelfRespawn.isRespawnInProgress())
            return;
        boolean redraw = false;
        if (busy.get()) {
            workingFrame.incrementAndGet();
            redraw = true;
        }
        if (statusOverrideExpiresAt > 0 && System.currentTimeMillis() >= statusOverrideExpiresAt) {
            clearStatusOverride();
            redraw = true;
        }
        if (redraw)
            redrawPrompt();
    }

    private void redrawPrompt() {
        if (SelfRespawn.isRespawnInProgress())
            return;
        terminalUi.redrawPrompt(decorate(promptEditor.snapshot()));
    }

    private volatile long realInputTokens;
    private volatile long realOutputTokens;

    private void addUsage(ai.pijota.messages.Usage usage) {
        if (usage == null)
            return;
        realInputTokens += usage.inputTokens();
        realOutputTokens += usage.outputTokens();
    }

    private String tokenStatus() {
        long tokens = cachedContextTokens();
        if (tokens <= 0)
            return "?/" + fmtCount((int) contextWindow);
        int pct = (int) Math.min(99, (tokens * 100) / Math.max(1, contextWindow));
        String pctStr = theme.styled(pct >= 90 ? "toolError" : pct >= 70 ? "toolLabel" : "statusText",
                                        "(" + pct + "%)");
        return fmtCount((int) tokens) + "/" + fmtCount((int) contextWindow) + " " + pctStr;
    }

    private int tokenPct() {
        return (int) Math.min(99, (cachedContextTokens() * 100) / Math.max(1, contextWindow));
    }

    private long cachedContextTokens() {
        long now = System.currentTimeMillis();
        int messageCount = session.messages().size();
        long tokens = cachedContextTokens;
        if (tokens >= 0 && cachedContextMessageCount == messageCount
                                        && now - cachedContextTokensAt < TOKEN_STATUS_CACHE_MS) {
            return tokens;
        }
        tokens = session.estimatedContextTokens();
        cachedContextTokens = tokens;
        cachedContextTokensAt = now;
        cachedContextMessageCount = messageCount;
        return tokens;
    }

    private static String gitBranch(String cwd) {
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

    private final class PendingRegion implements PromptRegion.Custom {
        @Override
        public int height(int width, PromptSnapshot ctx) {
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
