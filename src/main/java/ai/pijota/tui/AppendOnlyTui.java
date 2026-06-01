package ai.pijota.tui;

import ai.pijota.agent.AgentEvent;
import ai.pijota.agent.PijotaSession;
import ai.pijota.agent.SessionExporter;
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
import ai.pijota.llm.ProviderCatalog;
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
import static ai.pijota.util.Util.shellQuote;
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
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
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
    private static final String[] WORKING_FRAMES = {
        "●  ●●  ",
        " ● ●●  ",
        "  ●●●  ",
        "  ●● ● ",
        "  ●●  ●",
        "  ●● ● ",
        "  ●●●  ",
        " ● ●●  "
    };

    private AnsiTheme theme;
    private String provider;
    private String model;
    private String cwd;
    private final SettingsManager settings;
    private final PijotaSession session;
    private FileReferenceResolver fileRefs;
    private final List<SkillLoader.Skill> skills;
    private long contextWindow;
    private StreamingMarkdownFormatter markdown;
    private StreamingMarkdownFormatter thinkingMarkdown;
    private final Terminal terminal;
    private final TerminalCoordinator terminalUi;
    private final PromptEditor promptEditor;
    private final ScheduledExecutorService animator =
        Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("pijota-tui-anim").factory());
    private Thread inputThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final ExecutorService agentExecutor =
        Executors.newSingleThreadExecutor(Thread.ofPlatform().name("pijota-tui-agent").factory());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicLong busyGeneration = new AtomicLong(0);
    private String statusOverride;
    private final DialogHandler dialogs;

    @Override public void setStatus(String msg) {
        statusOverride = msg;
    }

    @Override public AnsiTheme theme() { return theme; }

    void setTheme(AnsiTheme loaded) {
        this.theme = loaded;
        terminalUi.setTheme(loaded);
        this.markdown.setTheme(theme);
        this.thinkingMarkdown.setTheme(theme.muted());
    }

    @Override public CompletableFuture<Boolean> confirm(String question) {
        return dialogs.confirm(question);
    }

    @Override public CompletableFuture<String> prompt(String question, String defaultValue) {
        return dialogs.prompt(question, defaultValue);
    }

    @Override public void alert(String message) {
        appendPlain(List.of(theme.styled("toolLabel", "alert"), message));
        redrawPrompt();
    }

    @Override public void appendStatus(String msg) {
        appendPlain(List.of(theme.styled("statusText", msg)));
    }

    void appendError(String msg) {
        appendPlain(List.of(theme.styled("toolError", msg)));
    }

    PijotaSession session() { return session; }
    String sessionName() { return sessionName; }
    void sessionName(String name) { sessionName = name; }
    SlashCommand extensionCommand(String cmd) { return extensionCommands.get(cmd); }
    void stop() { running.set(false); }

    void appendStatusError(String msg) {
        appendPlain(List.of(theme.styled("toolError", msg)));
    }

    private String busyPhase;
    private final AtomicInteger queuedFollowUps = new AtomicInteger(0);
    private long interruptArmedUntil;
    private long exitArmedUntil;
    private ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
    private boolean thinkingHeaderShown;
    private String sessionName;
    private Attributes originalAttributes;
    private final Terminal.SignalHandler originalIntHandler;
    private final Terminal.SignalHandler originalWinchHandler;
    private String lastContent = "";
    private String lastThinking = "";
    private boolean compactionStreamRendered;
    private boolean contentStarted;
    private final LineAccumulator contentLines = new LineAccumulator();
    private final LineAccumulator thinkingLines = new LineAccumulator();
    private final SlashCommandRegistry commands;
    private final TuiCommandController commandController;
    private final Map<String, SlashCommand> extensionCommands;
    private final Map<KeyChord, ExtensionKeybinding> extensionKeybindings;
    private final TuiSelectors selectors;
    private boolean loginIsLogout;
    private volatile boolean showSessionPickerOnStart;
    private final AtomicInteger workingFrame = new AtomicInteger();
    private final List<String> pendingFollowUps = new ArrayList<>();
    private final MessageBrowser messageBrowser;
    private PendingEditManager pendingEdit;
    private final Supplier<List<String>> extensionLinesSupplier;
    private final ThemeManager themeMgr = new ThemeManager(this);
    private final StreamProcessor streamProc = new StreamProcessor();
    private final TranscendenceOrchestrator transcendence;
    private final ShellCommandRunner shellCommands;
    private final SessionCommands sessionCommands;

    public void requestSessionPicker() { showSessionPickerOnStart = true; }
    private static final long INTERRUPT_WINDOW_MS = 1200;
    private static final long EXIT_WINDOW_MS = 800;
    private static final long TOKEN_STATUS_CACHE_MS = 1000;
    private volatile long cachedContextTokens = -1;
    private volatile long cachedContextTokensAt;
    private volatile int cachedContextMessageCount = -1;

    public AppendOnlyTui(PijotaSession session, AnsiTheme theme, String provider, String model,
                           List<SlashCommand> extensionCommands,
                            SettingsManager settings,
                            List<SkillLoader.Skill> skills,
                            Supplier<List<String>> extensionLinesSupplier,
                            List<ExtensionKeybinding> extensionKeybindings) throws IOException {
        this.commands = new SlashCommandRegistry(extensionCommands);
        this.promptEditor = new PromptEditor(commands);
        this.messageBrowser = new MessageBrowser(promptEditor, session::userMessages);
        this.session = session;
        this.theme = theme;
        this.dialogs = new DialogHandler(new DialogHandler.Host() {
            @Override public void appendPlain(List<String> lines) { AppendOnlyTui.this.appendPlain(lines); }
            @Override public String styled(String slot, String text) { return AppendOnlyTui.this.theme.styled(slot, text); }
            @Override public void setStatus(String status) { statusOverride = status; }
            @Override public void clearStatus() { statusOverride = null; }
            @Override public void redrawPrompt() { AppendOnlyTui.this.redrawPrompt(); }
        });
        this.shellCommands = new ShellCommandRunner(new ShellCommandRunner.Host() {
            @Override public List<String> promptEcho(String text) { return AppendOnlyTui.this.promptEcho(text); }
            @Override public void appendPlain(List<String> lines) { AppendOnlyTui.this.appendPlain(lines); }
            @Override public String styled(String slot, String text) { return AppendOnlyTui.this.theme.styled(slot, text); }
            @Override public long beginBusy(String phase) { return AppendOnlyTui.this.beginBusy(phase); }
            @Override public void endBusy(long generation) { AppendOnlyTui.this.endBusy(generation); }
            @Override public void redrawPrompt() { AppendOnlyTui.this.redrawPrompt(); }
            @Override public void fireUserBash(String command, boolean noContext) { session.fireUserBash(command, noContext); }
        });
        this.sessionCommands = new SessionCommands(new SessionCommands.Host() {
            @Override public PijotaSession session() { return session; }
            @Override public String styled(String slot, String text) { return theme.styled(slot, text); }
            @Override public void appendPlain(List<String> lines) { AppendOnlyTui.this.appendPlain(lines); }
            @Override public boolean busy() { return busy.get(); }
            @Override public String sessionName() { return AppendOnlyTui.this.sessionName; }
            @Override public void setSessionName(String name) { sessionName = name; }
            @Override public String provider() { return AppendOnlyTui.this.provider; }
            @Override public String model() { return AppendOnlyTui.this.model; }
            @Override public String cwd() { return AppendOnlyTui.this.cwd; }
            @Override public ThinkingLevel thinkingLevel() { return AppendOnlyTui.this.thinkingLevel; }
            @Override public SettingsManager settings() { return AppendOnlyTui.this.settings; }
            @Override public String tokenStatus() { return AppendOnlyTui.this.tokenStatus(); }
            @Override public void refreshSessionName() { AppendOnlyTui.this.refreshSessionName(); }
        });
        this.provider = provider;
        this.model = model;
        this.settings = settings;
        this.skills = skills;
        this.extensionLinesSupplier = extensionLinesSupplier != null ? extensionLinesSupplier : List::of;
        this.contextWindow = resolveContextWindow(provider, model);
        this.markdown = new StreamingMarkdownFormatter(theme);
        this.thinkingMarkdown = new StreamingMarkdownFormatter(theme.muted(), "codeBody");
        this.terminal = TerminalBuilder.builder().dumb(false).jna(false).jansi(false).build();
        this.originalIntHandler = this.terminal.handle(Terminal.Signal.INT, _ -> handleCtrlC());
        this.originalWinchHandler = this.terminal.handle(Terminal.Signal.WINCH, _ -> handleResize());
        this.transcendence = new TranscendenceOrchestrator(new TranscendenceOrchestrator.Host() {
            @Override public boolean busy() { return busy.get(); }
            @Override public void appendPlain(List<String> lines) { AppendOnlyTui.this.appendPlain(lines); }
            @Override public List<String> promptEcho(String text) { return AppendOnlyTui.this.promptEcho(text); }
            @Override public String styled(String slot, String text) { return theme.styled(slot, text); }
            @Override public long beginBusy(String phase) { return AppendOnlyTui.this.beginBusy(phase); }
            @Override public void endBusy(long generation) { AppendOnlyTui.this.endBusy(generation); }
            @Override public void redrawPrompt() { AppendOnlyTui.this.redrawPrompt(); }
            @Override public void resetStreamState() { AppendOnlyTui.this.resetStreamState(); }
            @Override public void showSessionWarning() { AppendOnlyTui.this.showSessionWarning(); }
            @Override public void suspendTui() { AppendOnlyTui.this.suspendTui(); }
            @Override public void resumeTui() { AppendOnlyTui.this.resumeTui(); }
            @Override public void flushTerminal() { terminal.writer().flush(); }
            @Override public void submitAgent(Runnable task) { agentExecutor.submit(task); }
            @Override public CompletableFuture<List<Message>> promptSession(String prompt) { return session.prompt(prompt); }
            @Override public Path sessionFile() { return session.sessionFile(); }
            @Override public void setStatusOverride(String status) { statusOverride = status; }
        });
        this.selectors = new TuiSelectors(
            this::confirmModelSelection,
            this::confirmSessionSelection,
            this::confirmThemeSelection,
            this::confirmSettingsSelection,
            () -> { if (loginIsLogout) confirmLogoutSelection(); else confirmLoginSelection(); },
            this::confirmForkSelection);
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
        appendPlain(List.of(
            theme.styled("bannerName", "pijota " + version)
                + theme.styled("bannerMeta", " · " + provider + "/" + model),
            theme.styled("statusText", "Type /help for available commands")
        ));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        running.set(false);
        shutdownLatch.countDown();
        if (inputThread != null && inputThread != Thread.currentThread()) inputThread.interrupt();
        shellCommands.interruptThread();
        agentExecutor.shutdownNow();
        animator.shutdownNow();
        if (SelfRespawn.isRespawnInProgress()) return;
        try { terminalUi.close(); } catch (Exception ignored) {}
        restoreTerminalState();
        SlashCommandTool.setQuestionHandler(null);
        try { terminal.close(); } catch (Exception | IOError ignored) {}
    }

    private void restoreTerminalState() {
        try {
            terminal.writer().print("\033[?2026l\033[?2004l\033[?25h\033[0m");
            terminal.writer().flush();
        } catch (Exception ignored) {}
        try {
            if (originalAttributes != null) terminal.setAttributes(originalAttributes);
        } catch (Exception | IOError ignored) {}
        try {
            if (originalIntHandler != null) terminal.handle(Terminal.Signal.INT, originalIntHandler);
            if (originalWinchHandler != null) terminal.handle(Terminal.Signal.WINCH, originalWinchHandler);
        } catch (Exception ignored) {}
    }

    private void readInputLoop() {
        while (running.get()) {
            if (SelfRespawn.isRespawnInProgress()) {
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                continue;
            }
            try {
                var key = InputDecoder.readKey(terminal);
                try {
                if (dialogs.handleConfirm(key)) continue;
                if (dialogs.handlePrompt(key)) continue;
                if (handleExtensionKeybinding(key)) continue;
                if ((key == Key.Special.ESC || key == Key.Special.DOUBLE_ESC) && selectors.anyActive()) {
                    selectors.deactivateAll();
                    statusOverride = null;
                    promptEditor.setText("");
                    redrawPrompt();
                    continue;
                }
                if (key == Key.Special.DOUBLE_ESC) {
                    handleEsc(true);
                    continue;
                }
                if (key == Key.Special.ESC) { handleEsc(false); continue; }
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
                if (selectors.handle(key, this::redrawPrompt)) continue;
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
                    if (key == Key.Special.UP) { messageBrowser.prev(); redrawPrompt(); continue; }
                    if (key == Key.Special.DOWN) { messageBrowser.next(); redrawPrompt(); continue; }
                    if (key == Key.Special.ENTER) { messageBrowser.exit(); redrawPrompt(); continue; }
                    if (key == Key.Special.ESC) { messageBrowser.cancel(); redrawPrompt(); continue; }
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
                    if (SelfRespawn.isRespawnInProgress()) continue;
                    statusOverride = null;
                    redrawPrompt();
                }
            } catch (IOException ignored) {
                running.set(false);
                break;
            }
        }
    }

    private boolean handleExtensionKeybinding(Key key) {
        if (key instanceof Key.Char) return false;
        if (isReservedKey(key)) return false;
        if (selectors.anyActive() || promptEditor.hasCompletions() || messageBrowser.isActive()) return false;
        var chord = toKeyChord(key);
        if (chord == null) return false;
        var binding = extensionKeybindings.get(chord);
        if (binding == null || binding.handler() == null) return false;
        try {
            binding.handler().run();
            redrawPrompt();
        } catch (Exception e) {
            statusOverride = "Keybinding error: " + ErrorUtil.message(e);
            redrawPrompt();
        }
        return true;
    }

    private static boolean isReservedKey(Key key) {
        return key == Key.Special.ESC || key == Key.Special.DOUBLE_ESC || key == Key.Special.CTRL_C
            || key == Key.Special.ENTER || key == Key.Special.NEWLINE || key == Key.Special.BACKSPACE
            || key == Key.Special.TAB || key == Key.Special.PASTE_START;
    }

    private static KeyChord toKeyChord(Key key) {
        if (!(key instanceof Key.Special special)) return null;
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
        if (dialogs.cancelQuestionIfIdle(busy.get())) return;
        if (!busy.get()) return;
        long now = System.currentTimeMillis();
        if (forceInterrupt || now <= interruptArmedUntil) {
            interruptArmedUntil = 0;
            statusOverride = "Interrupted";
            session.abort();
            flushStreamLines();
            resetStreamState();
            shellCommands.interruptProcess();
        } else {
            interruptArmedUntil = now + INTERRUPT_WINDOW_MS;
            statusOverride = "Press Esc again to interrupt";
        }
        redrawPrompt();
    }

    private void handleCtrlC() {
        long now = System.currentTimeMillis();
        if (now <= exitArmedUntil) {
            running.set(false);
            close();
            return;
        }
        promptEditor.setText("");
        exitArmedUntil = now + EXIT_WINDOW_MS;
        statusOverride = "Press Ctrl+C again to exit";
        redrawPrompt();
    }

    private void suspendTui() {
        terminal.writer().print("\033[?25h\033[0m\r\n");
        terminal.writer().flush();
        if (originalAttributes != null) terminal.setAttributes(originalAttributes);
    }

    private void resumeTui() {
        try {
            originalAttributes = terminal.enterRawMode();
        } catch (Exception ignored) {}
    }

    private void openPromptInEditor() {
        if (busy.get()) {
            statusOverride = "Editor unavailable while busy";
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
                statusOverride = "Editor exited with code " + code;
            }
        } catch (Exception e) {
            resumeTui();
            statusOverride = "Editor error: " + e.getMessage();
        } finally {
            if (file != null) {
                try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            }
            redrawPrompt();
        }
    }

    private void handleSubmit(String text) {
        String submitted = Util.stripSystemReminders(text).strip();
        if (submitted.isBlank()) return;
        submitted = fileRefs.expand(submitted);
        String answer = submitted;
        if (dialogs.submitQuestionAnswer(answer, () -> appendPlain(promptEcho(answer)))) return;
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
        exitArmedUntil = 0;
        long gen = beginBusy("waiting for model");
        redrawPrompt();
        agentExecutor.submit(() -> {
            try {
                resetStreamState();
                session.prompt(payload).join();
            } catch (CompletionException e) {
                statusOverride = "Error: " + ErrorUtil.message(e.getCause());
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
        interruptArmedUntil = 0;
        statusOverride = null;
        return busyGeneration.incrementAndGet();
    }

    private void endBusy(long generation) {
        if (!busyGeneration.compareAndSet(generation, 0)) return;
        busy.set(false);
        interruptArmedUntil = 0;
        statusOverride = null;
    }

    boolean runExtensionCommand(SlashCommand extCmd, String input, String cmd) {
        if (extCmd == null || extCmd.handler() == null) return false;
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
        session.consumeSessionWarning().ifPresent(warning ->
            appendPlain(List.of(theme.styled("toolError", warning))));
    }

    void showSettingsSelector() {
        if (settings == null) {
            appendPlain(List.of(theme.styled("toolError", "settings unavailable")));
            return;
        }
        selectors.activate(selectors.settings, settingsItems());
        promptEditor.setText("");
        redrawPrompt();
        return;
    }

    private List<SettingsItem> settingsItems() {
        var cfg = settings.config();
        boolean context = settings.contextFilesEnabled();
        boolean compact = settings.compactionEnabled();
        boolean shorten = settings.shortenPathsEnabled();
        return List.of(
            new SettingsItem("auto_compact", "Auto-compact", compact ? "on" : "off", compact),
            new SettingsItem("context_files", "Context files", context ? "on  next session" : "off  next session", context),
            new SettingsItem("shorten_paths", "Shorten paths", shorten ? "on" : "off", shorten),
            new SettingsItem("thinking_level", "Thinking", thinkingLevel.label(), true),
            new SettingsItem("hide_thinking", "Hide thinking", cfg.hideThinking() ? "on" : "off", cfg.hideThinking()),
            new SettingsItem("quiet_startup", "Quiet startup", cfg.quietStartup() ? "on  next start" : "off  next start", cfg.quietStartup())
        );
    }

    private void confirmSettingsSelection() {
        if (!selectors.settings.active() || selectors.settings.total() == 0) return;
        var chosen = selectors.settings.selectedItem();
        switch (chosen.id()) {
            case "auto_compact" -> settings.setCompactionEnabled(!settings.compactionEnabled());
            case "context_files" -> settings.setContextFilesEnabled(!settings.contextFilesEnabled());
            case "thinking_level" -> {
                thinkingLevel = thinkingLevel.next();
                session.setThinkingLevel(thinkingLevel);
            }
            case "hide_thinking" -> settings.setHideThinking(!settings.config().hideThinking());
            case "quiet_startup" -> settings.setQuietStartup(!settings.config().quietStartup());
            case "shorten_paths" -> settings.setShortenPathsEnabled(!settings.shortenPathsEnabled());
            default -> {}
        }
        selectors.settings.setItems(settingsItems());
        if (chosen.id().equals("auto_compact")) {
            session.setAutoCompactionEnabled(settings.compactionEnabled());
        }
        statusOverride = chosen.id().equals("context_files") || chosen.id().equals("quiet_startup")
            ? chosen.titleText() + " updated for next start/session"
            : chosen.titleText() + " updated";
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
        selectors.activate(selectors.login, items);
        promptEditor.setText("");
        redrawPrompt();
    }

    private void confirmLoginSelection() {
        if (!selectors.login.active() || selectors.login.total() == 0) return;
        var chosen = selectors.login.selectedItem();
        selectors.login.deactivate();
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
                    result = OAuthLogin.loginOpenAI(
                        o.deviceEndpoint(), o.tokenEndpoint(), o.oauthTokenEndpoint(), o.clientId());
                } else {
                    result = OAuthLogin.login(
                        o.deviceEndpoint(), o.tokenEndpoint(), o.clientId(), o.scope());
                }

                resumeTui();

                var oauthCfg = oauth.get();
                String tokenUrl = oauthCfg.oauthTokenEndpoint() != null && !oauthCfg.oauthTokenEndpoint().isBlank()
                    ? oauthCfg.oauthTokenEndpoint()
                    : oauthCfg.tokenEndpoint();
                var store = new OAuthTokenStore();
                store.save(chosen.id(), result.accessToken(), result.refreshToken(),
                    result.expiresAt(), result.accountId(), tokenUrl, oauthCfg.clientId());
                String defaultModel = ModelRegistry.defaultModelForProvider(chosen.id());
                if (defaultModel != null) {
                    settings.setDefaultModelForProvider(chosen.id(), defaultModel);
                }
                appendPlain(List.of(theme.styled("statusText",
                    System.lineSeparator() + "Logged in to " + chosen.titleText() + "  model: " + (defaultModel != null ? defaultModel : ""))));
                redrawPrompt();
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                resumeTui();
                appendPlain(List.of(theme.styled("toolError",
                    "Login failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))));
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
                items.add(new LoginProviderItem(p.id(), p.displayName(), tok != null ? "logged in" : "not logged in", false));
            }
        }
        if (items.isEmpty()) {
            appendPlain(List.of(theme.styled("toolError", "no providers to log out")));
            return;
        }
        loginIsLogout = true;
        selectors.activate(selectors.login, items);
        promptEditor.setText("");
        redrawPrompt();
    }

    private void confirmLogoutSelection() {
        if (!selectors.login.active() || selectors.login.total() == 0) return;
        var chosen = selectors.login.selectedItem();
        selectors.login.deactivate();
        promptEditor.setText("");
        redrawPrompt();
        var store = new OAuthTokenStore();
        store.remove(chosen.id());
        try { settings.removeApiKey(chosen.id()); } catch (IOException ignored) {}
        appendPlain(List.of(theme.styled("statusText", "Logged out of " + chosen.titleText())));
    }

    void showThemeSelector() {
        var items = themeMgr.discover();
        if (items.isEmpty()) {
            appendPlain(List.of(theme.styled("toolError", "no themes found")));
            return;
        }
        selectors.activate(selectors.theme, items);
        selectors.theme.selectFirst(ThemeItem::current);
        promptEditor.setText("");
        redrawPrompt();
    }

    private void confirmThemeSelection() {
        if (!selectors.theme.active() || selectors.theme.total() == 0) return;
        var chosen = selectors.theme.selectedItem();
        if (chosen.path().isBlank()) themeMgr.switchTo(chosen.name());
        else themeMgr.switchToPath(chosen.path());
        selectors.theme.deactivate();
        promptEditor.setText("");
        redrawPrompt();
    }

    private CompletableFuture<String> askQuestions(List<SlashCommandTool.Question> questions) {
        return dialogs.askQuestions(questions);
    }

    void showModelSelector() {
        if (busy.get()) {
            appendPlain(List.of(theme.styled("toolError", "/model is unavailable while a turn is running")));
            redrawPrompt();
            return;
        }
        var models = ModelRegistry.KNOWN_MODELS.stream()
            .filter(m -> settings == null || settings.resolveApiKey(m.provider()) != null)
            .toList();
        showModelSelector(models);
    }

    void showModelSelector(List<ModelInfo> models) {
        if (busy.get()) {
            appendPlain(List.of(theme.styled("toolError", "/model is unavailable while a turn is running")));
            redrawPrompt();
            return;
        }
        if (models.isEmpty()) {
            appendPlain(List.of(theme.styled("toolError", "no models found")));
            return;
        }
        var items = models.stream().map(m -> new ModelItem(m, m.id().equals(model))).toList();
        selectors.activate(selectors.model, items);
        selectors.model.selectFirst(i -> i.info().id().equals(model));
        promptEditor.setText("");
        redrawPrompt();
    }

    private void confirmModelSelection() {
        if (!selectors.model.active() || selectors.model.total() == 0) return;
        var chosen = selectors.model.selectedItem().info();
        switchToModel(chosen);
        selectors.model.deactivate();
        promptEditor.setText("");
        redrawPrompt();
    }

    private void showSessionPicker() {
        var sessions = session.resumableSessions();
        if (sessions.isEmpty()) {
            appendPlain(List.of(theme.styled("toolError", "no sessions found")));
            return;
        }
        var items = SessionItem.fromPaths(sessions);
        selectors.activate(selectors.session, items);
        promptEditor.setText("");
        redrawPrompt();
    }

    private void confirmSessionSelection() {
        if (!selectors.session.active() || selectors.session.total() == 0) return;
        var chosen = selectors.session.selectedItem();
        selectors.session.deactivate();
        promptEditor.setText("");
        try {
            session.load(chosen.path());
            restoreModelFromSession();
            refreshSessionName();
            appendPlain(List.of(theme.styled("statusText", "loaded session " + chosen.name())));
        } catch (IOException e) {
            appendPlain(List.of(theme.styled("toolError", "failed to load session: " + e.getMessage())));
        }
        redrawPrompt();
    }

    private ModelInfo nextModel(List<ModelInfo> models, int direction) {
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).provider().equals(provider) && models.get(i).id().equals(model)) {
                int next = (i + direction + models.size()) % models.size();
                return models.get(next);
            }
        }
        return models.isEmpty() ? null : models.getFirst();
    }

    private void cycleModel() {
        if (busy.get()) {
            statusOverride = "Model switch unavailable while busy";
            redrawPrompt();
            return;
        }
        var models = availableModels();
        if (models.size() <= 1) return;
        var chosen = nextModel(models, 1);
        switchToModel(chosen);
    }

    private void cycleModelBackward() {
        if (busy.get()) {
            statusOverride = "Model switch unavailable while busy";
            redrawPrompt();
            return;
        }
        var models = availableModels();
        if (models.size() <= 1) return;
        var chosen = nextModel(models, -1);
        switchToModel(chosen);
    }

    private List<ModelInfo> availableModels() {
        return ModelRegistry.KNOWN_MODELS.stream()
            .filter(m -> settings == null || settings.resolveApiKey(m.provider()) != null)
            .toList();
    }

    private void switchToModel(ModelInfo chosen) {
        String key = settings != null ? settings.resolveApiKey(chosen.provider()) : null;
        if (key == null || key.isBlank()) {
            appendPlain(List.of(theme.styled("toolError", "no API key for " + chosen.provider())));
            return;
        }
        applyModel(chosen.provider(), chosen.id(), key, chosen.contextWindow(), true);
        appendPlain(List.of(theme.styled("statusText",
            provider + "/" + chosen.id() + "  " + (int)(chosen.contextWindow() / 1024) + "K")));
    }

    private void applyModel(String newProvider, String newModel, String key, long newContextWindow, boolean persist) {
        provider = newProvider;
        model = newModel;
        contextWindow = newContextWindow > 0 ? newContextWindow : resolveContextWindow(newProvider, newModel);
        if (persist) session.switchModel(newProvider, newModel, key, contextWindow);
        else session.restoreModel(newProvider, newModel, key, contextWindow);
        updateWhoamiTool();
    }

    private void restoreModelFromSession() {
        if (settings == null) return;
        try {
            var selection = session.latestModelSelection();
            if (selection == null) return;
            String key = settings.resolveApiKey(selection.provider());
            if (key == null || key.isBlank()) {
                appendPlain(List.of(theme.styled("toolError", "session model unavailable: missing API key for " + selection.provider())));
                return;
            }
            applyModel(selection.provider(), selection.model(), key, selection.contextWindow(), false);
            appendPlain(List.of(theme.styled("statusText", "restored model " + provider + "/" + model)));
        } catch (IOException e) {
            appendPlain(List.of(theme.styled("toolError", "model restore failed: " + e.getMessage())));
        }
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
        appendPlain(List.of(theme.styled("toolLabel", "/compact"
            + (instructions.isBlank() ? "" : ": " + instructions))));
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
        var lines = new ArrayList<>(List.of(
            theme.styled("toolLabel", "shortcuts"),
            theme.styled("statusText", "Enter        submit prompt"),
            theme.styled("statusText", "Ctrl+J       newline"),
            theme.styled("statusText", "Tab          autocomplete"),
            theme.styled("statusText", "Shift+Tab    toggle thinking"),
            theme.styled("statusText", "Up/Down      history / navigate selector"),
            theme.styled("statusText", "Esc          interrupt (double to force)"),
            theme.styled("statusText", "Ctrl+C       clear input (double to exit)"),
            theme.styled("statusText", "Ctrl+K       delete to end"),
            theme.styled("statusText", "Ctrl+U       clear line"),
            theme.styled("statusText", "Ctrl+W       delete word"),
            theme.styled("statusText", "Ctrl+L       select model"),
            theme.styled("statusText", "Ctrl+P       cycle model"),
            theme.styled("statusText", "Ctrl+G       external editor"),
            theme.styled("statusText", "Ctrl+X Ctrl+E external editor"),
            theme.styled("statusText", "/ for commands"),
            theme.styled("statusText", "! run bash"),
            theme.styled("statusText", "!! run bash (no context)")));
        for (var binding : extensionKeybindings.values()) {
            lines.add(theme.styled("statusText", binding.key() + "       " + binding.description()));
        }
        appendPlain(lines);
    }

    void showContext() {
        sessionCommands.showContext();
    }

    void showSessionInfo() {
        sessionCommands.showSessionInfo();
    }

    void showWhoami() {
        var info = ModelRegistry.KNOWN_MODELS.stream()
            .filter(m -> m.id().equalsIgnoreCase(model))
            .findFirst()
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
            appendPlain(List.of(theme.styled("toolError", "usage: /load <path>")));
            return;
        }
        try {
            var file = java.nio.file.Path.of(path);
            if (!java.nio.file.Files.exists(file)) {
                appendPlain(List.of(theme.styled("toolError", "file not found: " + path)));
                return;
            }
            if (!session.messages().isEmpty())
                appendPlain(List.of(theme.styled("statusWarn", "loading session — current messages will be replaced")));
            int count = session.load(file);
            refreshSessionName();
            if (count == 0) {
                appendPlain(List.of(theme.styled("toolError", "no messages found in session file")));
                return;
            }
            appendPlain(List.of(theme.styled("statusText", "loaded " + count + " messages from " + path)));
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
            appendPlain(new TreeRenderer(theme).render(session.treeEntries(), session.branchEntries(), session.leafId()));
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
        selectors.activate(selectors.fork, items);
        selectors.fork.selectFirst(item -> item.number() == users.size());
        statusOverride = "Select message to fork from; type to filter, Enter forks, Esc cancels";
        redrawPrompt();
    }

    private void confirmForkSelection() {
        if (!selectors.fork.active() || selectors.fork.total() == 0) return;
        var chosen = selectors.fork.selectedItem();
        selectors.fork.deactivate();
        statusOverride = null;
        forkFromMessage(chosen.message(), "forked from message " + chosen.number());
    }

    private void forkFromMessage(Message.User user, String status) {
        try {
            session.forkFrom(user.id().toString());
            appendPlain(List.of(theme.styled("statusText", status)));
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "fork failed: " + e.getMessage())));
        }
    }

    void cloneSession() {
        sessionCommands.cloneSession();
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
                if (usage == null) busyPhase = null;
                String content = msg.content() == null ? "" : msg.content();
                String delta = deltaPrefix(content, lastContent);
                if (showThinking() && msg.thinking() != null && !msg.thinking().isBlank()) {
                    if (!thinkingHeaderShown) {
                        String label = "thinking " + thinkingLevel.label() + "...";
                        appendThinking(List.of(theme.styled("heading", "  " + label)));
                        appendThinking(List.of(theme.styled("hr", "  " + "─".repeat(40))));
                        thinkingHeaderShown = true;
                    }
                    String thinkingDelta = deltaPrefix(msg.thinking(), lastThinking);
                    appendThinking(thinkingLines.accept(thinkingDelta, softLineLimit()));
                    lastThinking = msg.thinking();
                }
                redrawPrompt();
                if (!delta.isEmpty()) {
                    if (!contentStarted) {
                        if (thinkingHeaderShown) {
                            thinkingLines.flush().ifPresent(line -> appendThinking(List.of(line)));
                            appendThinking(List.of(theme.styled("hr", "  " + "─".repeat(40))));
                        }
                        contentStarted = true;
                    }
                }
                appendContent(contentLines.accept(delta, softLineLimit()));
                lastContent = content;
                if (usage != null) {
                    addUsage(usage);
                    flushStreamLines();
                    redrawPrompt();
                }
            }
            case AgentEvent.ToolStart(var name, var toolCallId, var args) -> {
                flushStreamLines();
                pendingEdit.onToolStart(name, args);
                busyPhase = "tool " + name;
                if (pendingEdit.hasPath() && pendingEdit.oldText() != null
                    && "edit".equals(name) && pendingEdit.newText() != null
                    && !pendingEdit.oldText().equals(pendingEdit.newText())) {
                    appendPlain(new DiffRenderer(theme).render(
                        "edit preview", pendingEdit.filePath(), pendingEdit.oldText(), pendingEdit.newText(), terminal.getWidth()));
                    pendingEdit.setPreviewShown(true);
                }
                redrawPrompt();
            }
            case AgentEvent.ToolResult(Message.ToolResult msg) -> {
                flushStreamLines();
                pendingEdit.onToolResult(isEditWrite(msg), msg.isError());
                String slot = msg.isError() ? "toolError" : "toolResult";
                var parts = new ArrayList<String>();
                parts.add(theme.styled(msg.isError() ? "toolError" : "toolLabel", "●"));
                parts.add(theme.styled("statusText", msg.toolName()));
                String detail = ToolRender.detail(msg.toolName(), pendingEdit.toolArgs(), cwd,
                    settings != null && settings.shortenPathsEnabled());
                if (!detail.isBlank()) parts.add(theme.styled("statusText", detail));
                Tool.ToolResultView rendered = renderToolResultView(msg);
                String summary = rendered != null ? rendered.summary() : toolResultSummary(msg);
                if (!summary.isBlank()) parts.add(theme.styled(slot, summary));
                if ("bash".equals(msg.toolName())) {
                    Map<String, Object> details = msg.details() == null ? Map.of() : msg.details();
                    if (details.get("exitCode") instanceof Number ec) {
                        parts.add(theme.styled(ec.intValue() == 0 ? "statusText" : "toolError", "exit " + ec.intValue()));
                    }
                }
                if (isEditWrite(msg) && pendingEdit.hasEditResult() && !pendingEdit.previewShown()) {
                    appendPlain(new DiffRenderer(theme).render(
                        msg.toolName(), pendingEdit.filePath(), pendingEdit.oldText(), pendingEdit.newText(), terminal.getWidth()));
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
                int compactedMsgs = c.oldCount() - remaining;
                int estTokens = c.estimatedTokens();
                String reason = c.reason() != null && !c.reason().isBlank() ? " (" + c.reason() + ")" : "";
                if (!compactionStreamRendered && c.summary() != null && !c.summary().isBlank()) {
                    renderFinalCompactionSummary(c.summary());
                }
                appendPlain(List.of(theme.styled("statusText",
                    "[compacted " + c.oldCount() + " -> " + remaining
                    + " messages, removed " + compactedMsgs + ", ~" + fmtCount(estTokens)
                    + " est tokens before]" + reason)));
                compactionStreamRendered = false;
            }
            case AgentEvent.CompactionStream stream -> renderCompactionStream(stream.content(), stream.thinking());
            case AgentEvent.CompactionStreamEnd _ -> {
                flushStreamLines();
                resetStreamState();
            }
            case AgentEvent.Error(Throwable t) -> appendPlain(List.of(theme.styled("toolError", "error: " + ErrorUtil.message(t))));
            default -> {}
        }
    }

    private void appendContent(List<String> lines) {
        if (lines.isEmpty()) return;
        List<StyledLine> rendered;
        synchronized (this) {
            rendered = renderContent(lines);
        }
        terminalUi.appendLines(rendered);
    }

    private void renderCompactionStream(String content, String thinking) {
        String text = content == null ? "" : Util.stripSystemReminders(content);
        String delta = deltaPrefix(text, lastContent);
        if (!delta.isEmpty()) {
            appendContent(contentLines.accept(delta, softLineLimit()));
            lastContent = text;
            compactionStreamRendered = true;
        }
    }

    private void renderFinalCompactionSummary(String summary) {
        resetStreamState();
        appendContent(contentLines.accept(summary, softLineLimit()));
        flushStreamLines();
        resetStreamState();
    }

    private String renderToolBodyLine(String toolName, String line) {
        if ("todowrite".equals(toolName)) return renderTodoLine(line);
        if (line.startsWith("╰→")) {
            return theme.styled("statusText", "╰→")
                + theme.styled("commandMeta", line.substring(2));
        }
        if (line.equals("┴") || line.equals("┬")) return theme.styled("statusText", line);
        String bodySlot = line.startsWith("⋯") ? "commandMeta" : "statusText";
        return theme.styled(bodySlot, line);
    }

    private String renderTodoLine(String line) {
        String slot = null;
        if (line.startsWith("│ [✓]")) slot = "diffAdd";
        else if (line.startsWith("│ [~]")) slot = "statusWarn";
        else if (line.startsWith("│ [−]")) slot = "toolError";
        if (slot == null) return theme.styled("statusText", line);
        return theme.styled("statusText", "│ ") + theme.styled(slot, line.substring(2));
    }

    private List<StyledLine> renderContent(List<String> lines) {
        markdown.setWidth(terminal.getWidth() - 4);
        var out = new ArrayList<StyledLine>();
        for (String line : lines) {
            appendFilteredAssistantLine(line, out);
        }
        return out;
    }

    private String toolResultSummary(Message.ToolResult msg) {
        if (msg.isError()) {
            String content = msg.content();
            if (content == null || content.isBlank()) return "";
            return content.lines().limit(5).reduce("", (a, b) -> a + (a.isEmpty() ? "" : "\n") + b);
        }
        Map<String, Object> details = msg.details() == null ? Map.of() : msg.details();
        return switch (msg.toolName()) {
            case "grep" -> details.get("matchCount") != null
                ? details.get("matchCount") + " match(es)" : firstLine(msg.content());
            case "glob" -> details.get("resultCount") != null
                ? details.get("resultCount") + " result(s)" : firstLine(msg.content());
            case "read" -> details.get("totalLines") != null
                ? details.get("totalLines") + " line(s)" : firstLine(msg.content());
            case "task" -> details.get("resultCount") != null
                ? details.get("mode") + ": " + details.get("succeeded") + "/" + details.get("resultCount")
                + " ok" : firstLine(msg.content());
            default -> firstLine(msg.content());
        };
    }

    private Tool.ToolResultView renderToolResultView(Message.ToolResult msg) {
        if (!"bash".equals(msg.toolName()) && !"task".equals(msg.toolName())
            && !"todowrite".equals(msg.toolName())) return null;
        var args = pendingEdit.toolArgs() != null
            ? Util.parseJsonArgs(pendingEdit.toolArgs().get())
            : Map.<String, Object>of();
        return session.agentLoop().toolRegistry().get(msg.toolName())
            .map(tool -> tool.renderResult(args,
                new Tool.ToolResult(msg.content(), msg.isError(),
                    msg.details() == null ? Map.of() : msg.details()), cwd))
            .orElse(null);
    }

    private int softLineLimit() {
        return Math.max(40, terminal.getWidth() - 4);
    }

    private void appendThinking(List<String> lines) {
        if (lines.isEmpty()) return;
        List<StyledLine> rendered;
        synchronized (this) {
            rendered = renderThinking(lines);
        }
        terminalUi.appendLines(rendered);
    }

    private List<StyledLine> renderThinking(List<String> lines) {
        thinkingMarkdown.setWidth(terminal.getWidth() - 4);
        var out = new ArrayList<StyledLine>();
        for (String line : lines) out.addAll(thinkingMarkdown.format(line));
        return out;
    }

    private void flushStreamLines() {
        List<List<StyledLine>> batches = new ArrayList<>();
        synchronized (this) {
            if (showThinking()) thinkingLines.flush().ifPresent(line -> batches.add(renderThinking(List.of(line))));
            contentLines.flush().ifPresent(line -> batches.add(renderContent(List.of(line))));
            markdown.setWidth(terminal.getWidth() - 4);
            batches.add(markdown.flush());
            thinkingMarkdown.setWidth(terminal.getWidth() - 4);
            batches.add(thinkingMarkdown.flush());
        }
        for (var batch : batches) terminalUi.appendLines(batch);
    }

    private void resetStreamState() {
        contentLines.clear();
        thinkingLines.clear();
        streamProc.reset();
        contentStarted = false;
        thinkingHeaderShown = false;
        lastContent = "";
        lastThinking = "";
        markdown.reset();
        thinkingMarkdown.reset();
    }

    private void appendFilteredAssistantLine(String line, List<StyledLine> out) {
        String cleaned = streamProc.stripAndFilter(line);
        if (cleaned != null && !cleaned.isBlank()) out.addAll(markdown.format(cleaned));
    }

    void appendPlain(List<String> lines) {
        terminalUi.appendLines(lines.stream().map(StyledLine::of).toList());
    }

    private List<String> promptEcho(String text) {
        var out = new ArrayList<String>();
        String[] split = text.split("\\R", -1);
        for (int i = 0; i < split.length; i++) {
            out.add(theme.styled("promptEcho", i == 0 ? "> " : "| ") + split[i]);
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
        String modelStr = " " + provider + "/" + model
            + (thinkingLevel != ThinkingLevel.OFF ? " • " + thinkingLevel.label() : "");
        snapshot = snapshot.withCwd(this.cwd).withContextPct(tokenPct())
            .withPendingMessages(List.copyOf(pendingFollowUps))
            .withExtensionLines(mergedExtensionLines())
            .withPromptState(promptState(snapshot, status));
        if (!busy.get()) {
            String bar = tokens + modelStr;
            if (AnsiWidth.visibleWidth(bar) > terminal.getWidth()) bar = tokens + "\n" + modelStr;
            return snapshot.withStatus(bar).withWarning(status);
        }
        String frame = WORKING_FRAMES[Math.floorMod(workingFrame.get(), WORKING_FRAMES.length)];
        int queued = queuedFollowUps.get();
        String line = tokens + "  " + frame;
        if (busyPhase != null && !busyPhase.isBlank()) line += " " + busyPhase;
        if (queued > 0) line += "  queued " + queued;
        String bar = line + modelStr;
        if (AnsiWidth.visibleWidth(bar) > terminal.getWidth()) bar = line + "\n" + modelStr;
        return snapshot.withStatus(bar).withWarning(status);
    }

    private PromptSnapshot.PromptState promptState(PromptSnapshot snapshot, String status) {
        if (busy.get()) return PromptSnapshot.PromptState.BUSY;
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
                if (marker == null) marker = "```";
                else if ("```".equals(marker)) marker = null;
            } else if (stripped.startsWith("~~~")) {
                if (marker == null) marker = "~~~";
                else if ("~~~".equals(marker)) marker = null;
            }
        }
        return marker != null;
    }

    private List<String> mergedExtensionLines() {
        return extensionLinesSupplier.get();
    }

    private String currentStatusOverride() {
        String status = statusOverride;
        long now = System.currentTimeMillis();
        if (status != null && !dialogs.questionPending()
            && now > interruptArmedUntil && now > exitArmedUntil
            && !"Interrupted".equals(status)) {
            statusOverride = null;
            return null;
        }
        return status;
    }

    private static boolean isEditWrite(Message.ToolResult msg) {
        return "edit".equals(msg.toolName()) || "write".equals(msg.toolName());
    }

    private boolean showThinking() {
        return thinkingLevel != ThinkingLevel.OFF && (settings == null || !settings.config().hideThinking());
    }

    private void tickWorkingAnimation() {
        if (!running.get() || !busy.get() || SelfRespawn.isRespawnInProgress()) return;
        workingFrame.incrementAndGet();
        redrawPrompt();
    }

    private void redrawPrompt() {
        if (SelfRespawn.isRespawnInProgress()) return;
        terminalUi.redrawPrompt(decorate(promptEditor.snapshot()));
    }

    private volatile long realInputTokens;
    private volatile long realOutputTokens;

    private void addUsage(dev.langchain4j.model.output.TokenUsage usage) {
        if (usage == null) return;
        if (usage.inputTokenCount() != null) realInputTokens += usage.inputTokenCount();
        if (usage.outputTokenCount() != null) realOutputTokens += usage.outputTokenCount();
    }

    private String tokenStatus() {
        long tokens = cachedContextTokens();
        if (tokens <= 0) return "?/" + fmtCount((int) contextWindow);
        int pct = (int) Math.min(99, (tokens * 100) / Math.max(1, contextWindow));
        String pctStr = theme.styled(
            pct >= 90 ? "toolError" : pct >= 70 ? "toolLabel" : "statusText", pct + "%");
        return fmtCount((int) tokens) + "/" + fmtCount((int) contextWindow) + " (" + pctStr + ")";
    }

    private int tokenPct() {
        return (int) Math.min(99, (cachedContextTokens() * 100) / Math.max(1, contextWindow));
    }

    private long cachedContextTokens() {
        long now = System.currentTimeMillis();
        int messageCount = session.messages().size();
        long tokens = cachedContextTokens;
        if (tokens >= 0
            && cachedContextMessageCount == messageCount
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
            String out = ProcessRunner.command("git", "branch", "--show-current")
                .workDir(Path.of(cwd))
                .timeout(5, TimeUnit.SECONDS)
                .runCapture()
                .strip();
            if (!out.isEmpty()) return " (" + out + ")";
        } catch (Exception ignored) {}
        return "";
    }

    private static long resolveContextWindow(String provider, String model) {
        return ModelRegistry.KNOWN_MODELS.stream()
            .filter(m -> m.provider().equalsIgnoreCase(provider) && m.id().equalsIgnoreCase(model))
            .findFirst()
            .or(() -> ModelRegistry.KNOWN_MODELS.stream()
                .filter(m -> m.id().equalsIgnoreCase(model))
                .findFirst())
            .map(ModelInfo::contextWindow)
            .orElse(128_000L);
    }

    private final class PendingRegion implements PromptRegion.Custom {
        @Override
        public int height(int width, PromptSnapshot ctx) {
            return ctx.pendingMessages().isEmpty() ? 0 : ctx.pendingMessages().size();
        }

        @Override
        public CursorPos render(PrintWriter out, int width, AnsiTheme theme, PromptSnapshot ctx) {
            if (ctx.pendingMessages().isEmpty()) return NO_CURSOR;
            for (int i = 0; i < ctx.pendingMessages().size(); i++) {
                if (i > 0) out.print("\r\n");
                out.print('\r');
                out.print("\033[K");
                out.print(theme.styled("statusWarn", "queued  " + ctx.pendingMessages().get(i)));
            }
            return NO_CURSOR;
        }
    }
}
