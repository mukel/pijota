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
import ai.pijota.llm.ProviderCatalog;
import ai.pijota.messages.Message;
import ai.pijota.modes.SlashCommand;
import ai.pijota.theme.AnsiTheme;
import ai.pijota.tools.SlashCommandTool;
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
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class AppendOnlyTui implements AutoCloseable, UiContext {
    private static final int MAX_TREE_INDENT = 6;
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
    private CompletableFuture<String> pendingQuestionAnswer;
    private volatile CompletableFuture<Boolean> pendingConfirm;
    private volatile CompletableFuture<String> pendingPrompt;
    private StringBuilder pendingPromptText = new StringBuilder();
    private String pendingPromptDefault = "";

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
        var future = new CompletableFuture<Boolean>();
        pendingConfirm = future;
        appendPlain(List.of(theme.styled("toolLabel", "confirm"), question));
        statusOverride = "confirm (y/n)";
        redrawPrompt();
        return future;
    }

    @Override public CompletableFuture<String> prompt(String question, String defaultValue) {
        var future = new CompletableFuture<String>();
        pendingPrompt = future;
        pendingPromptText = new StringBuilder(defaultValue != null ? defaultValue : "");
        pendingPromptDefault = defaultValue != null ? defaultValue : "";
        appendPlain(List.of(theme.styled("toolLabel", "prompt"), question));
        statusOverride = "type your response (Enter to confirm, Esc to cancel)";
        redrawPrompt();
        return future;
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
    private volatile Process activeShellProcess;
    private volatile Thread shellCommandThread;
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
    private final AtomicLong contextTokens = new AtomicLong();
    private final List<String> pendingFollowUps = new ArrayList<>();
    private final MessageBrowser messageBrowser;
    private PendingEditManager pendingEdit;
    private final Supplier<List<String>> extensionLinesSupplier;
    private final ThemeManager themeMgr = new ThemeManager(this);
    private final StreamProcessor streamProc = new StreamProcessor();

    public void requestSessionPicker() { showSessionPickerOnStart = true; }
    private static final long INTERRUPT_WINDOW_MS = 1200;
    private static final long EXIT_WINDOW_MS = 800;

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
            if (settings == null || !settings.config().quietStartup()) {
                appendPlain(List.of(
                    theme.styled("bannerName", "pijota " + version) + "  [" + provider + "/" + model + "]",
                    theme.styled("bannerMeta", "cwd ") + cwd,
                    theme.styled("statusText", "Enter submit  |  Ctrl+J newline  |  Tab complete  |  Esc interrupt  |  Ctrl+C exit")
                ));
            }
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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        running.set(false);
        shutdownLatch.countDown();
        if (inputThread != null && inputThread != Thread.currentThread()) inputThread.interrupt();
        if (shellCommandThread != null) shellCommandThread.interrupt();
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
                if (handleConfirm(key)) continue;
                if (handlePrompt(key)) continue;
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

    private boolean handleConfirm(Key key) {
        var pc = pendingConfirm;
        if (pc == null || pc.isDone()) return false;
        if (key instanceof Key.Char c) {
            if (c.value() == 'y' || c.value() == 'Y') pc.complete(true);
            else if (c.value() == 'n' || c.value() == 'N') pc.complete(false);
            else return true;
        } else if (key == Key.Special.ESC) {
            pc.complete(false);
        } else {
            return true;
        }
        pendingConfirm = null;
        statusOverride = null;
        redrawPrompt();
        return true;
    }

    private boolean handlePrompt(Key key) {
        var pp = pendingPrompt;
        if (pp == null || pp.isDone()) return false;
        if (key instanceof Key.Char c) {
            pendingPromptText.append(c.value());
            redrawPrompt();
            return true;
        }
        return switch (key) {
            case Key.Special.ENTER -> {
                String result = pendingPromptText.isEmpty() ? pendingPromptDefault : pendingPromptText.toString();
                pp.complete(result);
                pendingPrompt = null;
                pendingPromptText = new StringBuilder();
                statusOverride = null;
                redrawPrompt();
                yield true;
            }
            case Key.Special.ESC, Key.Special.CTRL_C -> {
                pp.complete(pendingPromptDefault);
                pendingPrompt = null;
                pendingPromptText = new StringBuilder();
                statusOverride = null;
                redrawPrompt();
                yield true;
            }
            case Key.Special.BACKSPACE -> {
                if (!pendingPromptText.isEmpty()) {
                    pendingPromptText.setLength(pendingPromptText.length() - 1);
                    redrawPrompt();
                }
                yield true;
            }
            default -> true;
        };
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
        var pending = pendingQuestionAnswer;
        if (!busy.get() && pending != null && !pending.isDone()) {
            pending.cancel(true);
            pendingQuestionAnswer = null;
            statusOverride = null;
            redrawPrompt();
            return;
        }
        if (!busy.get()) return;
        long now = System.currentTimeMillis();
        if (forceInterrupt || now <= interruptArmedUntil) {
            interruptArmedUntil = 0;
            statusOverride = "Interrupted";
            session.abort();
            flushStreamLines();
            resetStreamState();
            if (activeShellProcess != null) ProcessRunner.killTree(activeShellProcess);
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
        var pendingAnswer = pendingQuestionAnswer;
        if (pendingAnswer != null && !pendingAnswer.isDone()) {
            appendPlain(promptEcho(submitted));
            pendingAnswer.complete(submitted);
            pendingQuestionAnswer = null;
            statusOverride = null;
            redrawPrompt();
            return;
        }
        String payload = submitted;
        session.stripSystemRemindersFromContext();
        if (payload.startsWith("/")) {
            commandController.handle(payload.strip());
            return;
        }
        if (payload.startsWith("!!")) {
            handleShellCommand(payload.substring(2).strip(), false);
            return;
        }
        if (payload.startsWith("!")) {
            handleShellCommand(payload.substring(1).strip(), true);
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

    private void handleShellCommand(String command, boolean addToContext) {
        if (command.isBlank()) return;
        appendPlain(promptEcho((addToContext ? "!" : "!!") + command));
        redrawPrompt();

        session.fireUserBash(command, !addToContext);

        long gen = beginBusy("running shell");
        redrawPrompt();
        shellCommandThread = Thread.ofPlatform().name("pijota-shell-command")
            .start(() -> {
            try {
                var result = ProcessRunner.command("/bin/sh", "-lc", command)
                    .redirectErrorStream(true)
                    .noTimeout()
                    .onStart(p -> { activeShellProcess = p; })
                    .run();
                var out = new ArrayList<String>();
                String slot = result.exitCode() == 0 ? "toolLabel" : "toolError";
                String suffix = result.exitCode() == 143 || result.exitCode() == 137
                    ? "  interrupted" : "  exit " + result.exitCode();
                out.add(theme.styled(slot, "! " + command + suffix));
                String output = result.stdout().stripTrailing();
                if (!output.isBlank()) {
                    for (String line : output.split("\\R", -1)) out.add(line);
                }
                appendPlain(out);
            } catch (Exception e) {
                appendPlain(List.of(theme.styled("toolError", "! " + command + "  error: " + e.getMessage())));
            } finally {
                activeShellProcess = null;
                shellCommandThread = null;
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
        return List.of(
            new SettingsItem("auto_compact", "Auto-compact", compact ? "on" : "off", compact),
            new SettingsItem("context_files", "Context files", context ? "on  next session" : "off  next session", context),
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

                var store = new OAuthTokenStore();
                store.save(chosen.id(), result.accessToken(), result.refreshToken(),
                    result.expiresAt(), result.accountId());
                String defaultModel = ModelRegistry.defaultModelForProvider(chosen.id());
                if (defaultModel != null) {
                    settings.saveApiKey(chosen.id(), result.accessToken(), defaultModel);
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
        var future = new CompletableFuture<String>();
        pendingQuestionAnswer = future;
        var lines = new ArrayList<String>();
        lines.add(theme.styled("toolLabel", "question"));
        for (int i = 0; i < questions.size(); i++) {
            var q = questions.get(i);
            if (questions.size() > 1) lines.add("");
            String prefix = questions.size() == 1 ? "" : "Q" + (i + 1) + "  ";
            lines.add(prefix + q.question());
            if (!q.options().isEmpty()) {
                for (int j = 0; j < q.options().size(); j++) {
                    var opt = q.options().get(j);
                    String desc = opt.description() == null || opt.description().isBlank()
                        ? "" : " - " + opt.description();
                    lines.add(String.format("    %d) %s%s", j + 1, opt.label(), desc));
                }
                lines.add(theme.styled("statusText",
                    q.multiple() ? "type numbers (comma-separated) or custom text (Esc cancel)"
                                 : "type a number or custom text (Esc cancel)"));
            } else {
                lines.add(theme.styled("statusText", "type your answer (Esc cancel)"));
            }
        }
        appendPlain(lines);
        statusOverride = "answer (" + questions.size() + ")";
        redrawPrompt();
        return future;
    }

    void showModelSelector() {
        var models = ModelRegistry.KNOWN_MODELS.stream()
            .filter(m -> settings == null || settings.resolveApiKey(m.provider()) != null)
            .toList();
        showModelSelector(models);
    }

    void showModelSelector(List<ModelInfo> models) {
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
        var models = availableModels();
        if (models.size() <= 1) return;
        var chosen = nextModel(models, 1);
        switchToModel(chosen);
    }

    private void cycleModelBackward() {
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
        model = chosen.id();
        provider = chosen.provider();
        String key = settings != null ? settings.resolveApiKey(chosen.provider()) : null;
        if (key == null) return;
        contextWindow = chosen.contextWindow();
        contextTokens.set(0);
        session.switchModel(chosen.provider(), chosen.id(), key, contextWindow);
        appendPlain(List.of(theme.styled("statusText",
            provider + "/" + chosen.id() + "  " + (int)(chosen.contextWindow() / 1024) + "K")));
    }

    void triggerCompaction(String instructions) {
        if (busy.get()) {
            appendPlain(List.of(theme.styled("toolError", "/compact unavailable while model is responding")));
            return;
        }
        appendPlain(List.of(theme.styled("toolLabel", "/compact"
            + (instructions.isBlank() ? "" : ": " + instructions))));
        session.compact(instructions);
    }

    void copyLastResponse() {
        String last = session.lastAssistantContent();
        if (last == null || last.isBlank()) {
            appendPlain(List.of(theme.styled("toolError", "no assistant response to copy")));
            return;
        }
        try {
            var clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new java.awt.datatransfer.StringSelection(last), null);
            appendPlain(List.of(theme.styled("statusText", "copied last response to clipboard")));
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "clipboard unavailable: " + e.getMessage())));
        }
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
        var msgs = session.messages();
        int systems = 0, users = 0, assistants = 0, tools = 0, compactions = 0;
        long sysChars = 0, userChars = 0, assistantChars = 0, toolChars = 0;
        for (var m : msgs) {
            switch (m) {
                case Message.System s -> { systems++; sysChars += s.content().length(); }
                case Message.User u -> { users++; userChars += u.content().length(); }
                case Message.Assistant a -> { assistants++; assistantChars += (a.content() != null ? a.content().length() : 0); }
                case Message.ToolResult tr -> { tools++; toolChars += tr.content().length(); }
                case Message.CompactionSummary _ -> compactions++;
                default -> {}
            }
        }
        long totalChars = sysChars + userChars + assistantChars + toolChars;
        long estTokens = totalChars / 4;
        var lines = new ArrayList<String>();
        lines.add(theme.styled("toolLabel", "context  " + msgs.size() + " msgs  " + fmtCount((int) estTokens)
            + " est tokens  " + tokenStatus()));
        lines.add(theme.styled("statusText", "  auto-compact  "
            + (settings == null || settings.compactionEnabled() ? "on" : "off")));
        if (systems > 0) lines.add(theme.styled("statusText",
            "  system     " + systems + " msg  " + fmtCount((int)(sysChars / 4)) + " est tokens"));
        lines.add(theme.styled("statusText",
            "  user       " + users + " msgs  " + fmtCount((int)(userChars / 4)) + " est tokens"));
        lines.add(theme.styled("statusText",
            "  assistant  " + assistants + " msgs  " + fmtCount((int)(assistantChars / 4)) + " est tokens"));
        lines.add(theme.styled("statusText",
            "  tool       " + tools + " msgs  " + fmtCount((int)(toolChars / 4)) + " est tokens"));
        if (compactions > 0) {
            lines.add(theme.styled("statusText", "  compactions  " + compactions));
            for (var m : msgs) {
                if (m instanceof Message.CompactionSummary cs) {
                    lines.add(theme.styled("hr", "─".repeat(40)));
                    lines.add(theme.styled("statusText", "  last summary:"));
                    for (String line : cs.summary().lines().toList()) {
                        lines.add(theme.styled("statusText", "  " + line));
                    }
                }
            }
        }
        appendPlain(lines);
    }

    void showSessionInfo() {
        refreshSessionName();
        appendPlain(List.of(
            theme.styled("toolLabel", "session"),
            theme.styled("statusText", "provider  " + provider),
            theme.styled("statusText", "model    " + model),
            theme.styled("statusText", "cwd      " + cwd),
            theme.styled("statusText", "tokens   " + tokenStatus()),
            theme.styled("statusText", "thinking " + thinkingLevel.label()),
            theme.styled("statusText", "compact  " + (settings == null || settings.compactionEnabled() ? "auto" : "off"))));
        if (sessionName != null)
            appendPlain(List.of(theme.styled("statusText", "name     " + sessionName)));
    }

    private void refreshSessionName() {
        try {
            sessionName = session.sessionName();
        } catch (IOException ignored) {
            sessionName = null;
        }
    }

    void saveSession(String input) {
        String path = input.length() > 5 ? input.substring(5).trim() : "";
        if (path.isBlank()) {
            String ts = java.time.Instant.now().toString().replace(":", "-").substring(0, 19);
            path = "pijota-session-" + ts + ".jsonl";
        }
        try {
            session.saveMessages(java.nio.file.Path.of(path));
            appendPlain(List.of(theme.styled("statusText", "session saved to " + path)));
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "save failed: " + e.getMessage())));
        }
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
        var sessions = session.listSessions();
        if (sessions.isEmpty()) {
            appendPlain(List.of(theme.styled("statusText", "no saved sessions")));
            return;
        }
        var lines = new ArrayList<String>();
        lines.add(theme.styled("toolLabel", "sessions (" + sessions.size() + ")"));
        for (var item : SessionItem.fromPaths(sessions)) {
            String id = item.name().length() > 8 ? item.name().substring(0, 8) : item.name();
            lines.add(theme.styled("statusText", "  " + id + "  " + item.detailText() + "  " + item.description()));
        }
        lines.add(theme.styled("statusText", "/load <id|path> to resume  /delete <id> to remove"));
        appendPlain(lines);
    }

    void deleteSession(String input) {
        String spec = input.length() > 7 ? input.substring(7).trim() : "";
        if (spec.isBlank()) {
            appendPlain(List.of(theme.styled("toolError", "usage: /delete <id|path>")));
            return;
        }
        try {
            session.deleteSession(spec);
            appendPlain(List.of(theme.styled("statusText", "deleted session: " + spec)));
        } catch (IOException e) {
            appendPlain(List.of(theme.styled("toolError", "delete failed: " + e.getMessage())));
        }
    }

    void startNewSession() {
        if (busy.get()) {
            appendPlain(List.of(theme.styled("toolError", "/new is unavailable while a turn is running")));
            return;
        }
        if (!session.messages().isEmpty())
            appendPlain(List.of(theme.styled("statusWarn", "starting new session — previous messages discarded")));
        try {
            session.newSession();
            sessionName = null;
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "session creation failed: " + e.getMessage())));
            return;
        }
        appendPlain(List.of(theme.styled("statusText", "started new session")));
    }

    void showTree() {
        if (session.sessionFile() == null) {
            appendPlain(List.of(theme.styled("toolError", "no active session")));
            return;
        }
        try {
            var tree = buildTreeView(session.treeEntries());
            var activePath = entryIds(session.branchEntries());

            var lines = new ArrayList<String>();
            lines.add(theme.styled("toolLabel", "conversation tree (" + tree.userCount() + " turns, "
                + tree.entries().size() + " entries)"));
            lines.add(theme.styled("statusText", "● current turn  · active branch  user N matches /fork N"));
            String leafId = session.leafId();
            var rootTurns = firstUserDescendants(tree.roots(), tree.children());
            if (rootTurns.isEmpty()) {
                lines.add(theme.styled("statusText", "no user turns yet"));
            }
            for (int i = 0; i < rootTurns.size(); i++) {
                appendTurnNode(lines, rootTurns.get(i), tree.children(), activePath, leafId, tree.userNumbers(),
                    "", i == rootTurns.size() - 1, 0);
            }
            appendPlain(lines);
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "tree: " + e.getMessage())));
        }
    }

    private TreeView buildTreeView(List<Map<String, Object>> entries) {
        var byId = new LinkedHashMap<String, Map<String, Object>>();
        var children = new LinkedHashMap<String, List<Map<String, Object>>>();
        var roots = new ArrayList<Map<String, Object>>();
        var userNumbers = new HashMap<String, Integer>();
        int userCount = 0;
        for (var entry : entries) {
            String id = entryId(entry);
            if (id == null) continue;
            byId.put(id, entry);
            if (isUserEntry(entry)) userNumbers.put(id, ++userCount);
        }
        for (var entry : entries) {
            String parentId = (String) entry.get("parentId");
            if (parentId == null || !byId.containsKey(parentId)) {
                roots.add(entry);
            } else {
                children.computeIfAbsent(parentId, _ -> new ArrayList<>()).add(entry);
            }
        }
        return new TreeView(entries, roots, children, userNumbers, userCount);
    }

    private Set<String> entryIds(List<Map<String, Object>> entries) {
        var ids = new HashSet<String>();
        for (var entry : entries) {
            String id = entryId(entry);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private void appendTurnNode(List<String> lines, Map<String, Object> entry,
                                Map<String, List<Map<String, Object>>> children,
                                Set<String> activePath, String leafId,
                                Map<String, Integer> userNumbers,
                                String prefix, boolean last, int depth) {
        String id = entryId(entry);
        boolean currentTurn = leafId != null && containsBeforeNextUser(entry, children, leafId::equals);
        boolean activeTurn = containsBeforeNextUser(entry, children, activePath::contains);
        String marker = currentTurn ? "●" : activeTurn ? "·" : " ";
        String connector = depth > MAX_TREE_INDENT ? "↳  " : last ? "└─ " : "├─ ";
        String line = prefix + connector + marker + " " + turnLabel(entry, userNumbers.get(id), children, depth);
        lines.add(theme.styled(currentTurn ? "toolLabel" : "statusText", line));

        var childEntries = firstUserDescendants(children.getOrDefault(id, List.of()), children);
        String childPrefix = depth >= MAX_TREE_INDENT ? prefix : prefix + (last ? "   " : "│  ");
        for (int i = 0; i < childEntries.size(); i++) {
            appendTurnNode(lines, childEntries.get(i), children, activePath, leafId, userNumbers,
                childPrefix, i == childEntries.size() - 1, depth + 1);
        }
    }

    private String turnLabel(Map<String, Object> entry, Integer userNumber,
                             Map<String, List<Map<String, Object>>> children, int depth) {
        String id = shortId(entryId(entry));
        var summary = summarizeTurn(entry, children);
        var label = new StringBuilder(id + "  user" + (userNumber != null ? " " + userNumber : "")
            + (depth > MAX_TREE_INDENT ? " d" + depth : "")
            + ": " + snippet((String) entry.get("content"), 78));
        if (!summary.assistant().isBlank()) {
            label.append("  ->  ").append(snippet(summary.assistant(), 72));
        }
        if (summary.toolCalls() > 0) {
            label.append("  [").append(summary.toolCalls()).append(" tool call")
                .append(summary.toolCalls() == 1 ? "" : "s").append("]");
        }
        if (!summary.tools().isEmpty()) {
            label.append("  [tools: ").append(String.join(", ", summary.tools())).append("]");
        }
        if (summary.errors() > 0) {
            label.append("  [").append(summary.errors()).append(" error")
                .append(summary.errors() == 1 ? "" : "s").append("]");
        }
        if (summary.compacted() > 0) {
            label.append("  [compacted ").append(summary.compacted()).append("]");
        }
        return label.toString();
    }

    private List<Map<String, Object>> firstUserDescendants(List<Map<String, Object>> entries,
                                                           Map<String, List<Map<String, Object>>> children) {
        var users = new ArrayList<Map<String, Object>>();
        for (var entry : entries) {
            if (isUserEntry(entry)) {
                users.add(entry);
            } else {
                users.addAll(firstUserDescendants(children.getOrDefault(entryId(entry), List.of()), children));
            }
        }
        return users;
    }

    private TurnSummary summarizeTurn(Map<String, Object> entry,
                                      Map<String, List<Map<String, Object>>> children) {
        var tools = new LinkedHashSet<String>();
        var state = new TurnSummaryState();
        summarizeTurnChildren(children.getOrDefault(entryId(entry), List.of()), children, tools, state);
        return new TurnSummary(state.assistant, state.toolCallIds.size(), List.copyOf(tools), state.errors, state.compacted);
    }

    private void summarizeTurnChildren(List<Map<String, Object>> entries,
                                       Map<String, List<Map<String, Object>>> children,
                                       Set<String> tools, TurnSummaryState state) {
        for (var entry : entries) {
            if (isUserEntry(entry)) continue;
            String type = (String) entry.getOrDefault("type", "message");
            String role = (String) entry.getOrDefault("role", "");
            if ("compaction".equals(type)) {
                Object compacted = entry.get("compactedCount");
                if (compacted instanceof Number n) state.compacted += n.intValue();
            } else if ("assistant".equals(role)) {
                Object toolCalls = entry.get("tool_calls");
                if (toolCalls instanceof List<?> list) {
                    for (var item : list) {
                        if (item instanceof Map<?, ?> m) {
                            Object callId = m.get("id");
                            state.toolCallIds.add(callId instanceof String s ? s : String.valueOf(m));
                        }
                    }
                }
                String content = (String) entry.get("content");
                if (content != null && !content.isBlank()) state.assistant = content;
            } else if ("tool".equals(role) || "tool_result".equals(role)) {
                String tool = String.valueOf(entry.getOrDefault("tool_name", "?"));
                tools.add(tool);
                if (Boolean.TRUE.equals(entry.get("is_error"))) state.errors++;
            }
            summarizeTurnChildren(children.getOrDefault(entryId(entry), List.of()), children, tools, state);
        }
    }

    private boolean containsBeforeNextUser(Map<String, Object> entry,
                                           Map<String, List<Map<String, Object>>> children,
                                           Predicate<String> predicate) {
        String id = entryId(entry);
        if (id != null && predicate.test(id)) return true;
        for (var child : children.getOrDefault(id, List.of())) {
            if (isUserEntry(child)) continue;
            if (containsBeforeNextUser(child, children, predicate)) return true;
        }
        return false;
    }

    private static String entryId(Map<String, Object> entry) {
        Object id = entry.get("id");
        return id instanceof String s ? s : null;
    }

    private static boolean isUserEntry(Map<String, Object> entry) {
        return "message".equals(entry.getOrDefault("type", "message"))
            && "user".equals(entry.getOrDefault("role", ""));
    }

    private record TurnSummary(String assistant, int toolCalls, List<String> tools, int errors, int compacted) {}

    private record TreeView(List<Map<String, Object>> entries,
                            List<Map<String, Object>> roots,
                            Map<String, List<Map<String, Object>>> children,
                            Map<String, Integer> userNumbers,
                            int userCount) {}

    private static final class TurnSummaryState {
        String assistant = "";
        final Set<String> toolCallIds = new HashSet<>();
        int errors;
        int compacted;
    }

    private static String shortId(String id) {
        if (id == null) return "????????";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String snippet(String content, int max) {
        if (content == null) return "";
        String s = content.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, Math.max(0, max - 1)) + "…" : s;
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
        if (busy.get()) {
            appendPlain(List.of(theme.styled("toolError", "/clone is unavailable while a turn is running")));
            return;
        }
        if (session.sessionFile() == null) {
            appendPlain(List.of(theme.styled("toolError", "no active session")));
            return;
        }
        try {
            Path newFile = session.cloneCurrentBranch();
            appendPlain(List.of(theme.styled("statusText", "cloned session to " + newFile.getFileName())));
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "clone failed: " + e.getMessage())));
        }
    }

    void handleTranscend(String args) {
        boolean testMode = false;
        boolean rollback = false;
        String worktreePath = null;
        String mergeBranch = null;
        var goalParts = new ArrayList<String>();

        var tokens = args.split("\\s+");
        int i = 0;
        while (i < tokens.length) {
            if (tokens[i].isEmpty()) { i++; continue; }
            switch (tokens[i]) {
                case "--test" -> testMode = true;
                case "--rollback" -> rollback = true;
                case "--worktree" -> { if (i + 1 < tokens.length) worktreePath = tokens[++i]; }
                case "--merge" -> { if (i + 1 < tokens.length) mergeBranch = tokens[++i]; }
                default -> goalParts.add(tokens[i]);
            }
            i++;
        }

        String goal = goalParts.isEmpty() ? null : stripWrappingQuotes(String.join(" ", goalParts));

        if (busy.get()) {
            appendPlain(List.of(theme.styled("toolError", "/transcend is unavailable while a turn is running (Esc Esc to interrupt)")));
            return;
        }

        if (goal != null) {
            submitTranscendGoal(goal, worktreePath);
            return;
        }

        if (rollback) {
            handleTranscendRollback();
            return;
        }

        if (mergeBranch != null && !mergeBranch.isEmpty()) {
            handleTranscendMerge(mergeBranch);
            return;
        }

        String effectiveWorktree = worktreePath != null ? worktreePath : System.getProperty("user.dir");
        handleTranscendBuild(effectiveWorktree, testMode);
    }

    private void handleTranscendBuild(String projectRoot, boolean testMode) {
        Path root = Path.of(projectRoot);
        if (!Files.exists(root.resolve("pom.xml"))) {
            appendPlain(List.of(theme.styled("toolError", "no pom.xml found at " + root)));
            return;
        }

        appendPlain(List.of(theme.styled("statusText", "[transcend] backing up current jar...")));
        redrawPrompt();

        try {
            Path currentJar = SelfRespawn.findCurrentJar();
            SelfRespawn.backupJar(currentJar);
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "[transcend] backup failed: " + e.getMessage())));
            return;
        }

        appendPlain(List.of(theme.styled("statusText", "[transcend] building " + root + "...")));
        redrawPrompt();

        ProcessRunner.Result buildResult;
        try {
            buildResult = SelfRespawn.buildJar(root);
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "[transcend] build error: " + e.getMessage())));
            return;
        }

        if (buildResult.exitCode() != 0 || buildResult.timedOut()) {
            String output = buildResult.stdout().isBlank()
                ? "[no output]" : buildResult.stdout().strip();
            appendPlain(List.of(
                theme.styled("toolError", "[transcend] build failed (exit " + buildResult.exitCode() + ")"),
                theme.styled("toolError", output)));
            appendPlain(List.of(theme.styled("statusText",
                "[transcend] backup jar preserved — fix errors and try again")));
            return;
        }

        Path jar = SelfRespawn.findJarIn(root);
        if (jar == null || !Files.exists(jar)) {
            appendPlain(List.of(theme.styled("toolError", "[transcend] jar not found after build at " + root.resolve("target"))));
            return;
        }

        appendPlain(List.of(theme.styled("statusText", "[transcend] build OK (" + jar.getFileName() + ")")));
        redrawPrompt();

        Path sessionFile = session.sessionFile();

        if (testMode) {
            appendPlain(List.of(theme.styled("statusText",
                "[transcend] launching test instance — exit test instance to return")));
            redrawPrompt();

            suspendTui();
            try {
                int ec = SelfRespawn.runInteractive(jar, sessionFile, true);
                resumeTui();
                appendPlain(List.of(theme.styled("statusText", "[transcend] test instance exited (code " + ec + ")")));
                appendPlain(List.of(theme.styled("statusText",
                    "backup: " + SelfRespawn.backupStatus() + " — /transcend --rollback to revert")));
            } catch (Exception e) {
                resumeTui();
                appendPlain(List.of(theme.styled("toolError", "[transcend] test instance error: " + e.getMessage())));
            }
            redrawPrompt();
            return;
        }

        appendPlain(List.of(theme.styled("statusText", "[transcend] restarting with new jar...")));
        redrawPrompt();
        terminal.writer().flush();
        SelfRespawn.respawn(jar, sessionFile, true);
        resumeTui();
        String err = SelfRespawn.lastRespawnError();
        if (err != null) appendPlain(List.of(theme.styled("toolError", "[transcend] " + err)));
        redrawPrompt();
    }

    private void submitTranscendGoal(String goal, String worktreePath) {
        String resolvedWorktree = worktreePath;
        String branchName = null;

        if (resolvedWorktree == null) {
            branchName = generateTranscendBranch(goal);
            resolvedWorktree = "../pijota-" + branchName.replace('/', '-');
        }

        appendPlain(promptEcho("/transcend " + goal));
        long gen = beginBusy("transcending");
        redrawPrompt();

        String finalWorktree = resolvedWorktree;
        String finalBranch = branchName;
        agentExecutor.submit(() -> {
            boolean promptCompleted = false;
            try {
                if (finalBranch != null) {
                    try {
                        ProcessRunner.Result wtResult = ProcessRunner.command(
                            "git", "worktree", "add", "-b", finalBranch, finalWorktree)
                            .redirectErrorStream(true)
                            .run();
                        if (wtResult.exitCode() != 0) {
                            appendPlain(List.of(
                                theme.styled("toolError", "worktree creation failed:"),
                                theme.styled("toolError", wtResult.stdout().strip())));
                            endBusy(gen);
                            redrawPrompt();
                            return;
                        }
                    } catch (IOException e) {
                        appendPlain(List.of(
                            theme.styled("toolError", "worktree creation error: " + e.getMessage())));
                        endBusy(gen);
                        redrawPrompt();
                        return;
                    }
                }

                resetStreamState();
                var fullPrompt = "TRANSCENDENCE GOAL: " + goal + "\n\n"
                    + "Implement this goal by modifying pijota's source code. "
                    + "A git worktree has been prepared at " + finalWorktree
                    + (finalBranch != null ? " on branch " + finalBranch : "")
                    + ". Use worktree=\"" + finalWorktree + "\" for ALL transcendence actions "
                    + "(build, test, respawn).\n\n"
                    + "Workflow:\n"
                    + "1. Edit source files under " + finalWorktree + "/src/main/java/ai/pijota/\n"
                    + "2. transcendence action=\"build\" worktree=\"" + finalWorktree + "\"\n"
                    + "3. transcendence action=\"test\" worktree=\"" + finalWorktree + "\"\n"
                    + "4. Verify only with the worktree jar, never with a bare `pijota` command. "
                    + "Use `java -jar " + finalWorktree + "/target/<built-jar> ...` for smoke checks. "
                    + "If the feature is TUI-only and cannot be automated, inspect the command dispatch code and continue.\n"
                    + "5. Respawn is mandatory after build/test pass. Do not end the turn before calling: "
                    + "transcendence action=\"respawn\" worktree=\"" + finalWorktree + "\""
                    + (finalBranch != null ? " mergeBranch=\"" + finalBranch + "\"" : "") + "\n"
                    + "6. Do not ask the user to merge; the replacement process will merge automatically.\n"
                    + "7. Ignore inconclusive verification from the wrong executable; fix real failures only.\n\n"
                    + "Work autonomously — do not ask for permission at each step.";
                session.prompt(fullPrompt).join();
                promptCompleted = true;
                finalizeTranscendGoal(finalWorktree, finalBranch);
            } catch (CompletionException e) {
                statusOverride = "Error: " + ErrorUtil.message(e.getCause());
            } finally {
                if (!SelfRespawn.isRespawnInProgress()) {
                    if (!promptCompleted) showSessionWarning();
                    endBusy(gen);
                    redrawPrompt();
                }
            }
        });
    }

    private void finalizeTranscendGoal(String worktree, String branch) {
        if (SelfRespawn.isPending() || SelfRespawn.isRespawnInProgress()) return;

        Path root = Path.of(worktree);
        appendPlain(List.of(theme.styled("statusText", "[transcend] finalizing worktree restart...")));
        redrawPrompt();

        ProcessRunner.Result buildResult;
        try {
            buildResult = SelfRespawn.buildJar(root);
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "[transcend] final build error: " + e.getMessage())));
            return;
        }
        if (buildResult.exitCode() != 0 || buildResult.timedOut()) {
            appendPlain(List.of(
                theme.styled("toolError", "[transcend] final build failed (exit " + buildResult.exitCode() + ")"),
                theme.styled("toolError", buildResult.stdout().isBlank() ? "[no output]" : buildResult.stdout().strip())));
            return;
        }

        ProcessRunner.Result testResult;
        try {
            testResult = ProcessRunner.command("mvn", "test")
                .workDir(root)
                .redirectErrorStream(true)
                .run();
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "[transcend] final test error: " + e.getMessage())));
            return;
        }
        if (testResult.exitCode() != 0 || testResult.timedOut()) {
            appendPlain(List.of(
                theme.styled("toolError", "[transcend] final tests failed (exit " + testResult.exitCode() + ")"),
                theme.styled("toolError", testResult.stdout().isBlank() ? "[no output]" : testResult.stdout().strip())));
            return;
        }

        Path jar = SelfRespawn.findJarIn(root);
        if (jar == null || !Files.exists(jar)) {
            appendPlain(List.of(theme.styled("toolError", "[transcend] final jar not found at " + root.resolve("target"))));
            return;
        }

        try {
            SelfRespawn.backupJar(SelfRespawn.findCurrentJar());
        } catch (Exception ignored) {}

        if (!SelfRespawn.requestRespawn(jar, branch, root)) {
            appendPlain(List.of(theme.styled("toolError", "[transcend] respawn request was already pending")));
            return;
        }

        appendPlain(List.of(theme.styled("statusText", "[transcend] restarting with worktree jar...")));
        redrawPrompt();
        terminal.writer().flush();
        SelfRespawn.maybeRespawn();
    }

    private static String generateTranscendBranch(String goal) {
        String slug = goal.toLowerCase()
            .replaceAll("[^a-z0-9\\s-]", "")
            .replaceAll("\\s+", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-|-$", "");
        if (slug.length() > 30) slug = slug.substring(0, 30).replaceAll("-$", "");
        if (slug.isBlank()) slug = "update";
        String ts = String.format("%tH%<tM%<tS", System.currentTimeMillis());
        return "feat/" + slug + "-" + ts;
    }

    private static String stripWrappingQuotes(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private void handleTranscendRollback() {
        if (!SelfRespawn.canRollback()) {
            appendPlain(List.of(theme.styled("toolError", "no backup jar — nothing to rollback to")));
            return;
        }

        Path jar = SelfRespawn.findCurrentJar();
        if (jar == null) {
            jar = SelfRespawn.findJarIn(Path.of(System.getProperty("user.dir")));
        }
        if (jar == null) {
            appendPlain(List.of(theme.styled("toolError", "cannot determine target jar for rollback")));
            return;
        }

        try {
            SelfRespawn.rollback(jar);
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "rollback failed: " + e.getMessage())));
            return;
        }

        appendPlain(List.of(theme.styled("statusText", "[transcend] restored backup jar — restarting...")));
        redrawPrompt();
        terminal.writer().flush();
        SelfRespawn.respawn(jar, session.sessionFile(), true);
        resumeTui();
        String err = SelfRespawn.lastRespawnError();
        if (err != null) appendPlain(List.of(theme.styled("toolError", "[transcend] " + err)));
        redrawPrompt();
    }

    private void handleTranscendMerge(String branch) {
        appendPlain(List.of(theme.styled("statusText", "[transcend] merging branch " + branch + "...")));
        redrawPrompt();

        ProcessRunner.Result mergeResult;
        try {
            mergeResult = SelfRespawn.mergeBranch(branch);
        } catch (Exception e) {
            appendPlain(List.of(theme.styled("toolError", "merge error: " + e.getMessage())));
            return;
        }

        if (mergeResult.exitCode() != 0) {
            String output = mergeResult.stdout().isBlank()
                ? "[no output]" : mergeResult.stdout().strip();
            appendPlain(List.of(
                theme.styled("toolError", "[transcend] merge failed (exit " + mergeResult.exitCode() + ")"),
                theme.styled("toolError", output)));
            return;
        }

        appendPlain(List.of(theme.styled("statusText", "[transcend] merge OK — building from merged tree...")));
        redrawPrompt();
        handleTranscendBuild(System.getProperty("user.dir"), false);
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
                String trace = ToolRender.trace(name, args, cwd);
                if (!trace.isBlank()) appendPlain(List.of(theme.styled("statusText", trace)));
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
                String detail = ToolRender.detail(msg.toolName(), pendingEdit.toolArgs());
                if (!detail.isBlank()) parts.add(theme.styled("statusText", detail));
                String summary = toolResultSummary(msg);
                if (!summary.isBlank()) parts.add(theme.styled(slot, summary));
                if (isEditWrite(msg) && pendingEdit.hasEditResult() && !pendingEdit.previewShown()) {
                    appendPlain(new DiffRenderer(theme).render(
                        msg.toolName(), pendingEdit.filePath(), pendingEdit.oldText(), pendingEdit.newText(), terminal.getWidth()));
                }
                appendPlain(List.of(String.join("  ", parts)));
                pendingEdit.clear();
            }
            case AgentEvent.Compaction c -> {
                contextTokens.set(0);
                int compactedMsgs = c.oldCount() - c.compactedMessages().size();
                int estTokens = c.totalChars() / 4;
                var lines = new ArrayList<String>();
                String reason = c.reason() != null && !c.reason().isBlank() ? " (" + c.reason() + ")" : "";
                lines.add(theme.styled("statusText",
                    "[compacted " + compactedMsgs + " messages → "
                    + c.compactedMessages().size() + " msgs, ~" + fmtCount(estTokens) + " est tokens]" + reason));
                if (c.summary() != null && !c.summary().isBlank()) {
                    for (String line : c.summary().lines().toList()) {
                        lines.add(theme.styled("statusText", "  " + line));
                    }
                }
                appendPlain(lines);
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
        if (pendingPrompt != null && !pendingPrompt.isDone()) {
            String promptLine = "\u25b6 " + pendingPromptText.toString() + "\033[7m \033[27m";
            status = status != null ? status + "\n" + promptLine : promptLine;
        }
        String modelStr = " " + provider + "/" + model
            + (thinkingLevel != ThinkingLevel.OFF ? " • " + thinkingLevel.label() : "");
        snapshot = snapshot.withCwd(this.cwd).withContextPct(tokenPct())
            .withPendingMessages(List.copyOf(pendingFollowUps))
            .withExtensionLines(mergedExtensionLines());
        if (!busy.get()) return snapshot.withStatus(modelStr).withWarning(status);
        String frame = WORKING_FRAMES[Math.floorMod(workingFrame.get(), WORKING_FRAMES.length)];
        int queued = queuedFollowUps.get();
        String line = tokens + "  " + frame;
        if (busyPhase != null && !busyPhase.isBlank()) line += " " + busyPhase;
        if (queued > 0) line += "  queued " + queued;
        String bar = line + modelStr;
        if (AnsiWidth.visibleWidth(bar) > terminal.getWidth()) bar = line + "\n" + modelStr;
        return snapshot.withStatus(bar).withWarning(status);
    }

    private List<String> mergedExtensionLines() {
        return extensionLinesSupplier.get();
    }

    private String currentStatusOverride() {
        String status = statusOverride;
        long now = System.currentTimeMillis();
        if (status != null && !isQuestionPending()
            && now > interruptArmedUntil && now > exitArmedUntil
            && !"Interrupted".equals(status)) {
            statusOverride = null;
            return null;
        }
        return status;
    }

    private boolean isQuestionPending() {
        return pendingQuestionAnswer != null;
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

    private void addUsage(dev.langchain4j.model.output.TokenUsage usage) {
        if (usage.inputTokenCount() != null) contextTokens.set(usage.inputTokenCount());
    }

    private String tokenStatus() {
        long tokens = contextTokens.get();
        if (tokens <= 0) tokens = session.estimatedContextTokens();
        if (tokens <= 0) return "?/" + fmtCount((int) contextWindow);
        int pct = (int) Math.min(99, (tokens * 100) / Math.max(1, contextWindow));
        String pctStr = theme.styled(
            pct >= 90 ? "toolError" : pct >= 70 ? "toolLabel" : "statusText", pct + "%");
        return fmtCount((int) tokens) + "/" + fmtCount((int) contextWindow) + " (" + pctStr + ")";
    }

    private int tokenPct() {
        return (int) Math.min(99, (contextTokens.get() * 100) / Math.max(1, contextWindow));
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
