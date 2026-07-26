package ai.pijota;

import ai.pijota.agent.PijotaSession;
import ai.pijota.agent.SessionManager;
import ai.pijota.agent.SystemPrompt;
import ai.pijota.util.Proxies;
import ai.pijota.cli.Args;
import ai.pijota.config.ProviderCatalog;
import ai.pijota.config.ProjectTrust;
import ai.pijota.config.SettingsManager;
import ai.pijota.config.TrustStore;
import ai.pijota.extensions.ExtensionEvent;
import ai.pijota.extensions.ExtensionRegistry;
import ai.pijota.extensions.examples.BtwExtension;
import ai.pijota.extensions.examples.ContextFilesExtension;
import ai.pijota.extensions.examples.CoreCommandsExtension;
import ai.pijota.extensions.examples.FilterOutputExtension;
import ai.pijota.extensions.examples.GoalExtension;
import ai.pijota.extensions.examples.InlineBashExtension;
import ai.pijota.extensions.examples.ScheduleExtension;
import ai.pijota.extensions.examples.SecurityExtension;
import ai.pijota.extensions.examples.TodoExtension;
import ai.pijota.extensions.examples.TokenSpeedExtension;
import ai.pijota.extensions.examples.WebBrowseExtension;
import ai.pijota.extensions.examples.WhimsicalExtension;
import ai.pijota.extensions.jobs.JobsExtension;
import ai.pijota.extensions.mcp.McpExtension;
import ai.pijota.extensions.plan.PlanExtension;
import ai.pijota.extensions.prompts.PromptTemplateExtension;
import ai.pijota.extensions.skills.SkillExtension;
import ai.pijota.extensions.task.TaskExtension;
import ai.pijota.extensions.tmux.TmuxExtension;
import ai.pijota.extensions.transcend.TranscendenceExtension;
import ai.pijota.llm.LlmService;
import ai.pijota.llm.ModelRegistry;
import ai.pijota.llm.ModelResolver;
import ai.pijota.llm.ThinkingLevel;
import ai.pijota.tools.BashTool;
import ai.pijota.tools.ToolPolicy;
import ai.pijota.tools.ToolRegistry;
import ai.pijota.tools.WhoamiTool;
import ai.pijota.util.ProcessRunner;

import ai.pijota.util.SelfRespawn;
import ai.pijota.util.StartupTimer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Path;

public final class AppBootstrap {
    private static final Logger logger = LoggerFactory.getLogger(AppBootstrap.class);
    private static final Set<String> READ_ONLY_TOOLS = Set.of("read", "ls", "grep", "glob", "webfetch", "websearch",
                                    "question", "whoami");

    record Result(LlmService llm, ToolRegistry tools, ExtensionRegistry extensions, PijotaSession session,
                                    SessionManager sessions, SettingsManager settings, Args args, String cwd) {
    }

    static Result bootstrap(Args parsedArgs) throws Exception {
        return bootstrap(parsedArgs, new SettingsManager());
    }

    // package-visible for tests so callers can inject a SettingsManager backed by a temp directory.
    static Result bootstrap(Args parsedArgs, SettingsManager settings) throws Exception {
        StartupTimer.mark("bootstrap start");
        // Mirror any configured HTTP proxy into the JVM proxy properties so URLConnection-based paths and providers
        // whose client we can't configure directly (Gemini) route through it too, not just our explicit HttpClients.
        Proxies.installSystemProperties();
        String cwd = System.getProperty("user.dir");

        // Overlay any user-defined providers (settings.json "providers") onto the catalog before resolving the model,
        // so a custom/local provider's base URL, api type, and default model are visible to every lookup below.
        ProviderCatalog.registerUserProviders(settings.config().providers());
        StartupTimer.mark("settings/providers loaded");

        var resolved = ModelResolver.resolve(parsedArgs, settings);
        String provider = resolved.provider();
        String model = resolved.model();
        String apiKey = resolved.apiKey();
        StartupTimer.mark("model resolved");

        var llm = new LlmService(provider, model, settings::resolveApiKey);
        StartupTimer.mark("llm initialized");

        var sessions = new SessionManager(settings.sessionDir());
        var sessionRef = new AtomicReference<PijotaSession>();
        // Register everything; the tool policy (below) decides what's active. --no-tools etc. filter, not skip
        // registration, so the flags compose with the allow/deny lists in one place.
        var tools = ToolRegistry.createDefault();
        var bashTool = new BashTool(settings.effectiveShellPath(), settings.config().shellCommandPrefix(),
                                        () -> bashEnvironment(llm, sessionRef.get()));
        tools.registerBuiltin(bashTool);
        StartupTimer.mark("tools registered");
        String effectiveThinking = parsedArgs.thinkingLevel();
        if (effectiveThinking == null || effectiveThinking.isBlank()) {
            effectiveThinking = settings.config().thinkingLevel();
        }
        if (effectiveThinking != null && !effectiveThinking.isBlank()) {
            llm.setThinkingLevel(ThinkingLevel.fromString(effectiveThinking));
        }

        var ext = new ExtensionRegistry();
        llm.setModelChangeCallback((oldModel, newModel) -> ext.fireModelSelect(newModel, oldModel, "set"));
        ext.setToolRegistry(tools);
        ext.setCwd(Path.of(cwd));
        // settings.projectDir() always resolves to a real path (configured dir, else the git root of cwd).
        ext.setProjectDir(Path.of(settings.projectDir()));
        ext.setLlm(llm);
        configureExtensionEnablement(ext, parsedArgs, settings);
        boolean projectTrusted = resolveProjectTrust(cwd, parsedArgs, settings);
        loadDiscoveredExtensions(ext, parsedArgs, cwd, projectTrusted);

        // Niche tool, opt-in (--whoami) — gated through the same resolution as extensions.
        if (ext.isEnabled("whoami", false))
            tools.registerBuiltin(new WhoamiTool());

        // Built-in (shipped) extensions — skipped by --no-extensions (all off) or --no-builtin-extensions (keep only
        // discovered). Otherwise each is registered here and ExtensionRegistry.register() still gates it via
        // isEnabled()
        // on the declared default + settings + flags. (TranscendenceExtension is also JVM-only via its reason.)
        if (!parsedArgs.noExtensions() && !parsedArgs.noBuiltinExtensions()) {
            ext.register(new TaskExtension());
            ext.register(new PlanExtension());
            ext.register(new TranscendenceExtension()); // opt-in --transcendence
            ext.register(new InlineBashExtension());
            ext.register(new FilterOutputExtension());
            ext.register(new SecurityExtension());
            ext.register(new WhimsicalExtension());
            ext.register(new GoalExtension());
            ext.register(new ScheduleExtension());
            ext.register(new McpExtension());
            ext.register(new TodoExtension()); // opt-in --todo
            ext.register(new TokenSpeedExtension()); // opt-in --token-speed
            ext.register(new WebBrowseExtension()); // opt-in --browse[=engine]
            ext.register(new BtwExtension()); // /btw side-conversation (slash commands only)
            ext.register(new TmuxExtension()); // opt-in --tmux
            ext.register(new JobsExtension()); // opt-in --jobs
            ext.register(new SkillExtension(parsedArgs.skillPaths())); // --no-skills disables
            ext.register(new PromptTemplateExtension(parsedArgs.promptTemplatePaths())); // --no-prompt-templates
                                                                                         // disables
            ext.register(new ContextFilesExtension(cwd)); // --no-context-files / context_files setting disables
        }
        StartupTimer.mark("extensions registered");

        tools.applyPolicy(toolPolicy(parsedArgs));

        String appendPrompt = parsedArgs.appendSystemPrompts().isEmpty()
                                        ? null
                                        : String.join("\n", parsedArgs.appendSystemPrompts());
        String systemPrompt = SystemPrompt.build(parsedArgs.systemPrompt(), appendPrompt, cwd,
                                        settings.effectiveShellPath(), ext.systemPromptContributions());
        ext.setSystemPrompt(systemPrompt);
        StartupTimer.mark("system prompt built");

        int contextWindow = (int) ModelRegistry.resolveContextWindow(provider, model);

        int maxTurns = parsedArgs.maxTurns() != null ? parsedArgs.maxTurns() : settings.maxTurns();
        var session = new PijotaSession(llm, tools, sessions, ext, systemPrompt, cwd, parsedArgs.noSession(),
                                        contextWindow, settings.config().compaction(), maxTurns);
        sessionRef.set(session);
        StartupTimer.mark("session initialized");
        ext.setSessionFile(session::sessionFile);
        // Generic built-in commands (e.g. /model) usable by every frontend — registered once the session exists.
        ext.register(new CoreCommandsExtension(session, settings));
        boolean loadedSession = false;
        try {
            if (parsedArgs.sessionPath() != null) {
                session.load(parsedArgs.sessionPath());
                restoreSessionModel(session, settings);
                restoreSessionThinkingLevel(session);
                loadedSession = true;
            } else if (parsedArgs.recoverSession()) {
                Path recovery = SelfRespawn.readRecoverySession();
                if (recovery != null) {
                    session.load(recovery);
                    restoreSessionModel(session, settings);
                    restoreSessionThinkingLevel(session);
                    loadedSession = true;
                    SelfRespawn.clearRecoveryFile();
                } else {
                    logger.warn("No recovery session found — starting fresh");
                }
            } else if (parsedArgs.continueSession()) {
                session.continueLastSession();
                restoreSessionModel(session, settings);
                restoreSessionThinkingLevel(session);
                loadedSession = true;
            }
        } catch (IOException e) {
            logger.warn("Failed to load session ({}); starting fresh", e.getMessage());
        }
        ext.fire(new ExtensionEvent.SessionStart(session.sessionFile()));
        if (loadedSession) {
            session.checkCacheTarget();
        } else {
            session.refreshCacheMetadata();
            session.recordInitialModelState();
        }
        mergeTranscendWorktree(parsedArgs);
        StartupTimer.mark("bootstrap complete");

        // One-line record of what the agent actually started with — the first thing to check when diagnosing "why did
        // it behave like that": resolved provider/model, enabled tool count, and cwd.
        logger.info("bootstrap ready · {}/{} · {} tool(s) · cwd={}", provider, model, tools.activeToolNames().size(),
                                        cwd);

        return new Result(llm, tools, ext, session, sessions, settings, parsedArgs, cwd);
    }

    private static boolean resolveProjectTrust(String cwd, Args parsedArgs, SettingsManager settings) {
        if (parsedArgs.noApproveProject())
            return false;
        var trust = new TrustStore();
        if (parsedArgs.approveProject()) {
            trust.trust(cwd);
            return true;
        }
        if (trust.isTrusted(cwd))
            return true;
        if (!ProjectTrust.hasProtectedResources(cwd))
            return false;
        String fallback = settings.config().defaultProjectTrust() == null
                                        ? "ask"
                                        : settings.config().defaultProjectTrust().trim().toLowerCase(Locale.ROOT);
        if ("always".equals(fallback)) {
            trust.trust(cwd);
            return true;
        }
        if ("never".equals(fallback) || !interactiveTuiLaunch(parsedArgs))
            return false;
        Console console = System.console();
        if (console == null)
            return false;
        String answer = console.readLine("Trust project resources in %s? [y/N] ", cwd);
        if (answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"))) {
            trust.trust(cwd);
            return true;
        }
        return false;
    }

    private static boolean interactiveTuiLaunch(Args args) {
        return !args.agent() && !args.worker() && !args.print() && args.promptArgs().isEmpty()
                                        && args.outputMode() != Args.OutputMode.RPC
                                        && !args.extensionFlags().containsKey("web")
                                        && !args.extensionFlags().containsKey("telegram")
                                        && !args.extensionFlags().containsKey("acp");
    }

    private static Map<String, String> bashEnvironment(LlmService llm, PijotaSession session) {
        var env = new LinkedHashMap<String, String>();
        env.put("PIJOTA_PROVIDER", null);
        env.put("PIJOTA_MODEL", null);
        env.put("PIJOTA_REASONING_LEVEL", null);
        env.put("PIJOTA_SESSION_FILE", null);
        env.put("PIJOTA_SESSION_ID", null);
        env.put("PIJOTA_CODING_AGENT", "true");
        env.put("PIJOTA_PROVIDER", llm.provider());
        env.put("PIJOTA_MODEL", llm.model());
        var thinking = llm.thinkingLevel();
        if (thinking != null)
            env.put("PIJOTA_REASONING_LEVEL", thinking.name().toLowerCase(Locale.ROOT));
        if (session != null && session.sessionFile() != null) {
            Path file = session.sessionFile();
            env.put("PIJOTA_SESSION_FILE", file.toString());
            env.put("PIJOTA_SESSION_ID", sessionId(file));
        }
        return env;
    }

    private static String sessionId(Path sessionFile) {
        String name = sessionFile.getFileName().toString();
        return name.endsWith(".jsonl") ? name.substring(0, name.length() - ".jsonl".length()) : name;
    }

    private static void configureExtensionEnablement(ExtensionRegistry ext, Args parsedArgs, SettingsManager settings) {
        // Resolve extension enablement uniformly: declared default → settings.json "extensions" → CLI --x / --no-x.
        // Fold the legacy typed --no-* flags into the SAME resolution so they keep working as aliases (the --tools
        // registry switch and --no-extensions discovery toggle are separate axes and stay above). Install this before
        // loading ANY extension, because ExtensionRegistry.register() gates immediately.
        var flags = new java.util.LinkedHashMap<>(parsedArgs.extensionFlags());
        if (parsedArgs.noSkills())
            flags.put("no-skills", "true");
        if (parsedArgs.noPromptTemplates())
            flags.put("no-prompt-templates", "true");
        if (parsedArgs.noContextFiles() || !settings.contextFilesEnabled())
            flags.put("no-context-files", "true"); // also honors the legacy "context_files" setting
        ext.setExtensionFlags(flags);
        ext.setExtensionsConfig(settings.config().extensions());
    }

    private static void loadDiscoveredExtensions(ExtensionRegistry ext, Args args, String cwd, boolean projectTrusted) {
        if (args.noExtensions())
            return;
        ext.loadServiceLoaderExtensions();
        ext.loadFromDirectory(Path.of(System.getProperty("user.home"), ".pijota", "extensions"));
        loadProjectExtensions(ext, cwd, projectTrusted, ".pi");
        loadProjectExtensions(ext, cwd, projectTrusted, ".pijota");
        for (String path : args.extensionPaths())
            ext.loadFromPath(Path.of(path));
    }

    private static void loadProjectExtensions(ExtensionRegistry ext, String cwd, boolean trusted, String dirName) {
        Path dir = Path.of(cwd, dirName, "extensions");
        if (trusted) {
            ext.loadFromDirectory(dir);
        } else if (dir.toFile().isDirectory()) {
            logger.info("Skipped untrusted project extensions in {} — run /trust to enable them.", dir);
        }
    }

    private static ToolPolicy toolPolicy(Args args) {
        var scope = args.noTools() || args.readOnly()
                                        ? ToolPolicy.Scope.NONE
                                        : args.noBuiltinTools()
                                                                        ? ToolPolicy.Scope.EXTENSION_ONLY
                                                                        : ToolPolicy.Scope.ALL;
        Set<String> allow = args.readOnly() && args.enabledTools().isEmpty()
                                        ? READ_ONLY_TOOLS
                                        : Set.copyOf(args.enabledTools());
        return new ToolPolicy(scope, allow, Set.copyOf(args.excludeTools()));
    }

    private static void mergeTranscendWorktree(Args args) {
        String branch = args.extensionFlags().get("transcend-merge-branch");
        String worktree = args.extensionFlags().get("transcend-worktree");
        if ((branch == null || branch.isBlank()) && (worktree == null || worktree.isBlank()))
            return;

        try {
            if (branch != null && !branch.isBlank()) {
                var merge = SelfRespawn.mergeBranch(branch.strip());
                if (merge.exitCode() != 0) {
                    logger.warn("Automatic merge failed:\n{}", merge.stdout().strip());
                    return;
                }
            }

            if (worktree != null && !worktree.isBlank()) {
                if (!applyWorktreeDiff(Path.of(worktree.strip())))
                    return;

                ProcessRunner.command("git", "worktree", "remove", "--force", worktree.strip())
                                                .redirectErrorStream(true).run();
            }
        } catch (Exception e) {
            logger.warn("Automatic merge failed", e);
        }
    }

    private static void restoreSessionModel(PijotaSession session, SettingsManager settings) throws IOException {
        var selection = session.latestModelSelection();
        if (selection == null)
            return;
        var resolved = ModelRegistry.resolve(selection, settings);
        if (resolved == null) {
            logger.warn("Cannot restore model {}/{} — missing API key", selection.provider(), selection.model());
            return;
        }
        session.restoreModel(resolved.provider(), resolved.model(), resolved.apiKey(), resolved.contextWindow());
    }

    private static void restoreSessionThinkingLevel(PijotaSession session) throws IOException {
        var level = session.latestThinkingLevel();
        if (level != null)
            session.restoreThinkingLevel(level);
    }

    private static boolean applyWorktreeDiff(Path worktree) throws IOException {
        var diff = ProcessRunner.command("git", "diff", "--binary", "HEAD").workDir(worktree).redirectErrorStream(true)
                                        .maxOutputBytes(10 * 1024 * 1024).run();
        if (diff.exitCode() != 0) {
            logger.warn("Failed to collect worktree diff:\n{}", diff.stdout().strip());
            return false;
        }
        if (diff.stdout().isBlank())
            return true;

        var apply = ProcessRunner.command("git", "apply", "--3way", "--whitespace=nowarn").feedStdin(diff.stdout())
                                        .redirectErrorStream(true).maxOutputBytes(10 * 1024 * 1024).run();
        if (apply.exitCode() != 0) {
            logger.warn("Failed to apply worktree changes:\n{}", apply.stdout().strip());
            return false;
        }
        return true;
    }
}
