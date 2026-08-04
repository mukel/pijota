package org.pijota;

import org.pijota.agent.PijotaSession;
import org.pijota.agent.SessionManager;
import org.pijota.agent.SystemPrompt;
import org.pijota.cli.ArgParser;
import org.pijota.cli.Args;
import org.pijota.config.ProjectTrust;
import org.pijota.config.ProviderCatalog;
import org.pijota.config.SettingsManager;
import org.pijota.config.TrustStore;
import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.ExtensionRegistry;
import org.pijota.extensions.examples.BtwExtension;
import org.pijota.extensions.examples.ContextFilesExtension;
import org.pijota.extensions.examples.CoreCommandsExtension;
import org.pijota.extensions.examples.FilterOutputExtension;
import org.pijota.extensions.examples.GoalExtension;
import org.pijota.extensions.examples.InlineBashExtension;
import org.pijota.extensions.examples.ScheduleExtension;
import org.pijota.extensions.examples.SecurityExtension;
import org.pijota.extensions.examples.TodoExtension;
import org.pijota.extensions.examples.TokenSpeedExtension;
import org.pijota.extensions.examples.WebBrowseExtension;
import org.pijota.extensions.examples.WhimsicalExtension;
import org.pijota.extensions.jobs.JobRegistry;
import org.pijota.extensions.jobs.JobsExtension;
import org.pijota.extensions.mcp.McpExtension;
import org.pijota.extensions.plan.PlanExtension;
import org.pijota.extensions.prompts.PromptTemplateExtension;
import org.pijota.extensions.skills.SkillExtension;
import org.pijota.extensions.suggest.SuggestExtension;
import org.pijota.extensions.task.TaskExtension;
import org.pijota.extensions.task.TaskTool;
import org.pijota.extensions.tmux.TmuxExtension;
import org.pijota.extensions.transcend.TranscendenceExtension;
import org.pijota.llm.LlmService;
import org.pijota.llm.ModelRegistry;
import org.pijota.llm.ModelResolver;
import org.pijota.llm.ThinkingLevel;
import org.pijota.tools.BashTool;
import org.pijota.tools.ToolPolicy;
import org.pijota.tools.ToolRegistry;
import org.pijota.tools.WhoamiTool;
import org.pijota.util.PijotaHome;
import org.pijota.util.ProcessRunner;
import org.pijota.util.Proxies;
import org.pijota.util.SelfRespawn;
import org.pijota.util.StartupTimer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

public final class AppBootstrap {
    private static final Logger logger = LoggerFactory.getLogger(AppBootstrap.class);
    private static final Set<String> READ_ONLY_TOOLS = Set.of("read", "ls", "grep", "glob", "webfetch", "websearch",
                                    "question", "whoami");

    /** {@code jobs} is the shared background-job registry, or {@code null} when the jobs extension is disabled. */
    record Result(LlmService llm, ToolRegistry tools, ExtensionRegistry extensions, PijotaSession session,
                                    SessionManager sessions, SettingsManager settings, Args args, String cwd,
                                    JobRegistry jobs) {
    }

    static Result bootstrap(Args parsedArgs) throws Exception {
        // Before the SettingsManager, which reads and may write inside it.
        PijotaHome.ensurePrivate();
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

        var llm = new LlmService(provider, model, apiKeyResolver(provider, apiKey, settings));
        StartupTimer.mark("llm initialized");

        var sessions = new SessionManager(settings.sessionDir());
        var sessionRef = new AtomicReference<PijotaSession>();
        // Register everything; the tool policy (below) decides what's active. --no-tools etc. filter, not skip
        // registration, so the flags compose with the allow/deny lists in one place.
        var tools = ToolRegistry.createDefault();
        var bashTool = new BashTool(settings.effectiveShellPath(), settings.config().shellCommandPrefix(),
                                        () -> bashEnvironment(llm, sessionRef.get()));
        tools.registerBuiltin(bashTool);
        // One registry for all background jobs: jobs_run-launched AND user-backgrounded (ctrl+b ctrl+b) commands.
        // Shared with the jobs extension so jobs_output/jobs_kill see adopted processes.
        var jobRegistry = new JobRegistry();
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
        // Published before anything that gates on it: skills, sub-agents and MCP servers all load later, and each
        // re-deriving the answer is how -a and -na came to mean different things to different loaders.
        ProjectTrust.resolve(cwd, projectTrusted);
        loadDiscoveredExtensions(ext, parsedArgs, cwd, projectTrusted);

        // Niche tool, opt-in (--whoami), gated through the same resolution as extensions.
        if (ext.isEnabled("whoami", false))
            tools.registerBuiltin(new WhoamiTool());

        // Built-in (shipped) extensions, skipped by --no-extensions (all off) or --no-builtin-extensions (keep only
        // discovered). Otherwise each is registered here and ExtensionRegistry.register() still gates it via
        // isEnabled()
        // on the declared default + settings + flags. (TranscendenceExtension is also JVM-only via its reason.)
        if (!parsedArgs.noExtensions() && !parsedArgs.noBuiltinExtensions()) {
            ext.register(new TaskExtension(jobRegistry));
            // A pijota killed mid-batch leaves sub-agent worktrees behind, still registered in the user's own
            // repository, and nothing else ever removes them. Off the startup path; wired here rather than in the
            // extension because tests construct extensions freely and must not touch a real repo.
            java.util.concurrent.CompletableFuture.runAsync(() -> TaskTool.sweepStaleWorktrees(cwd));
            ext.register(new PlanExtension());
            ext.register(new SuggestExtension()); // opt-in --suggest
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
            // --no-jobs removes the model's four jobs_* tools; /jobs, /kill and Ctrl+B come from the core
            // extension and stay.
            ext.register(new JobsExtension(jobRegistry, settings.effectiveShellPath()));
            ext.register(new SkillExtension(parsedArgs.skillPaths())); // --no-skills disables
            ext.register(new PromptTemplateExtension(parsedArgs.promptTemplatePaths())); // --no-prompt-templates
                                                                                         // disables
            ext.register(new ContextFilesExtension(cwd)); // --no-context-files / context_files setting disables
        }
        StartupTimer.mark("extensions registered");
        // Only now: every extension has had its chance to register, so a name still unaccounted for is a typo. The
        // frontend subcommands reach the same map without being extensions.
        ext.warnAboutUnknownFlags(ArgParser.FRONTEND_SUBCOMMANDS);

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
        // Generic built-in commands (e.g. /model) usable by every frontend, registered once the session exists.
        ext.register(new CoreCommandsExtension(session, settings, jobRegistry));
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
                    // Only the log knew, so --recover after a crash looked like it had worked: a normal startup, and
                    // the loss discovered later when the model has no memory of the conversation. Say it on screen,
                    // and name the flags that do resume an ordinary session.
                    System.err.println("pijota: no interrupted self-rebuild to recover; starting fresh."
                                                    + " For an ordinary session use -c (continue last) or -r (pick).");
                }
            } else if (parsedArgs.continueSession()) {
                session.continueLastSession();
                restoreSessionModel(session, settings);
                restoreSessionThinkingLevel(session);
                loadedSession = true;
            } else if (parsedArgs.forkPath() != null) {
                // Load, then immediately branch into a copy, so the run continues from the named session's history
                // with the named session itself left untouched -- which is the whole point of forking rather than
                // resuming. The flag was parsed and stored and nothing read it, so --fork <id> silently started a
                // fresh session instead.
                session.load(parsedArgs.forkPath());
                session.cloneCurrentBranch();
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

        // One-line record of what the agent actually started with, the first thing to check when diagnosing "why did
        // it behave like that": resolved provider/model, enabled tool count, and cwd.
        logger.info("bootstrap ready · {}/{} · {} tool(s) · cwd={}", provider, model, tools.activeToolNames().size(),
                                        cwd);

        // Always handed over. Backgrounding used to be withdrawn along with --no-jobs, on the grounds that a
        // backgrounded command would be stranded with no jobs_* tools to observe or kill it -- but /jobs and /kill are
        // registered by the core extension now, so the user can still see it and stop it. --no-jobs takes a capability
        // from the model, which is what its own help text describes, and nothing from the user.
        return new Result(llm, tools, ext, session, sessions, settings, parsedArgs, cwd, jobRegistry);
    }

    private static boolean resolveProjectTrust(String cwd, Args parsedArgs, SettingsManager settings) {
        return resolveProjectTrust(cwd, parsedArgs, settings, AppBootstrap::askOnConsole);
    }

    /**
     * Whether this folder's own settings, extensions and skills may load, which is to say whether its code may run.
     *
     * <p>
     * Package-private, with the question itself passed in: {@code never} and {@code ask} both refuse without a console,
     * so with {@code System.console()} hardwired the two are indistinguishable anywhere a test can reach, and deleting
     * the {@code never} branch changed nothing observable. The seam exists so that branch can be held to its meaning.
     *
     * @param ask
     *            poses the trust question and answers it; only consulted for {@code ask} in an interactive TUI launch.
     */
    static boolean resolveProjectTrust(String cwd, Args parsedArgs, SettingsManager settings, Predicate<String> ask) {
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
        if (!ask.test(cwd))
            return false;
        trust.trust(cwd);
        return true;
    }

    private static boolean askOnConsole(String cwd) {
        Console console = System.console();
        if (console == null)
            return false;
        console.printf("%s", trustPrompt(cwd));
        String answer = console.readLine("Trust this folder? [y/N] ");
        return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
    }

    /**
     * The first thing a new user ever sees, so it introduces pijota and explains the choice.
     *
     * <p>
     * It used to be the bare line {@code Trust project resources in <path>? [y/N]}, printed before the banner: a
     * security question asked of someone with no basis to answer it, defaulting to no, and never saying what no costs.
     * Declining is genuinely safe and mostly invisible, which is exactly why it has to be said out loud -- otherwise
     * the folder's agents and skills are quietly absent and their absence reads as a missing feature.
     */
    static String trustPrompt(String cwd) {
        return "\npijota " + PijotaVersion.current() + "\n\n"
                                        + "This folder carries its own pijota resources -- .pijota/ (agents, skills,\n"
                                        + "commands) and context files such as AGENTS.md. Trusting it means running\n"
                                        + "code from this repository.\n\n"
                                        + "  y   trust it; remembered for next time, change later with /trust\n"
                                        + "  N   skip them; pijota works normally, these are simply ignored\n\n" + cwd
                                        + "\n";
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
        var flags = new LinkedHashMap<>(parsedArgs.extensionFlags());
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
        // PijotaHome, not user.home: extensions are code, and an alternate home that still loads the real home's
        // extensions is not the isolated home it claims to be.
        ext.loadFromDirectory(PijotaHome.resolve("extensions"));
        loadProjectExtensions(ext, cwd, projectTrusted, ".pijota");
        for (String path : args.extensionPaths())
            ext.loadFromPath(Path.of(path));
    }

    private static void loadProjectExtensions(ExtensionRegistry ext, String cwd, boolean trusted, String dirName) {
        Path dir = Path.of(cwd, dirName, "extensions");
        if (trusted) {
            ext.loadFromDirectory(dir);
        } else if (dir.toFile().isDirectory()) {
            logger.info("Skipped untrusted project extensions in {} · run /trust to enable them.", dir);
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

    /**
     * How the run looks up a provider's credential. Normally the settings store; when {@code --api-key} was given, that
     * key for the provider it was given with, and the store for any other. The flag used to be parsed, resolved, and
     * then dropped on the floor -- the resolved value was assigned to a local nothing read -- so it silently ran on the
     * stored credential instead, or, with nothing stored, failed as "apiKey cannot be null or blank" while holding the
     * key it had just been handed.
     *
     * <p>
     * Scoped to the one provider on purpose: switching model mid-session must not hand a key meant for one provider to
     * a different one.
     */
    static Function<String, String> apiKeyResolver(String provider, String flagKey, SettingsManager settings) {
        if (flagKey == null || flagKey.isBlank())
            return settings::resolveApiKey;
        return p -> p != null && p.equals(provider) ? flagKey : settings.resolveApiKey(p);
    }

    private static void restoreSessionModel(PijotaSession session, SettingsManager settings) throws IOException {
        var selection = session.latestModelSelection();
        if (selection == null)
            return;
        var resolved = ModelRegistry.resolve(selection, settings);
        if (resolved == null) {
            logger.warn("Cannot restore model {}/{} · missing API key", selection.provider(), selection.model());
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
