package ai.pijota;

import ai.pijota.agent.PijotaSession;
import ai.pijota.agent.SessionManager;
import ai.pijota.agent.SystemPrompt;
import ai.pijota.cli.Args;
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
import ai.pijota.llm.ThinkingLevel;
import ai.pijota.tools.BashTool;
import ai.pijota.tools.ToolRegistry;
import ai.pijota.tools.WhoamiTool;
import ai.pijota.util.CliErrorReporter;
import ai.pijota.util.ProcessRunner;

import ai.pijota.util.SelfRespawn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

final class AppBootstrap {
    private static final Logger logger = LoggerFactory.getLogger(AppBootstrap.class);

    record Result(LlmService llm, ToolRegistry tools, ExtensionRegistry extensions, PijotaSession session,
                                    SessionManager sessions, SettingsManager settings, Args args, String cwd) {
    }

    static Result bootstrap(Args parsedArgs) throws Exception {
        return bootstrap(parsedArgs, new SettingsManager());
    }

    // package-visible for tests so callers can inject a SettingsManager backed by a temp directory.
    static Result bootstrap(Args parsedArgs, SettingsManager settings) throws Exception {
        String cwd = System.getProperty("user.dir");

        // Overlay any user-defined providers (settings.json "providers") onto the catalog before resolving the model,
        // so a custom/local provider's base URL, api type, and default model are visible to every lookup below.
        ai.pijota.config.ProviderCatalog.registerUserProviders(settings.config().providers());

        String apiKey = parsedArgs.apiKey();

        // Per-role model: print/-p uses the "print" alias when set and no explicit --model was given, so
        // non-interactive
        // runs can default to a cheaper/faster model (e.g. alias print -> alias/fast). Other roles (sub-agents,
        // summarize) already pick their model from their own spec/alias.
        boolean usePrintAlias = parsedArgs.model() == null && parsedArgs.print() && settings.config().aliases() != null
                                        && settings.config().aliases().containsKey("print");
        // ArgParser splits "alias/<name>" into provider="alias" + model="<name>", so resolve the print role the same
        // way (provider=ALIAS, model="print") rather than passing a bare "alias/print" string, which
        // resolveProviderModel
        // would take literally.
        var resolved = usePrintAlias
                                        ? ModelRegistry.resolveProviderModel(ModelRegistry.ALIAS_PROVIDER, "print",
                                                                        settings)
                                        : ModelRegistry.resolveProviderModel(parsedArgs.provider(), parsedArgs.model(),
                                                                        settings);
        // "alias" is reserved: if it survived resolution, the named alias is undefined — fail clearly instead of
        // silently falling through to the default provider.
        if (ModelRegistry.ALIAS_PROVIDER.equalsIgnoreCase(resolved.provider()))
            throw new IllegalStateException("Unknown model alias '" + parsedArgs.model()
                                            + "'. Define it under \"aliases\" in settings.json (name -> provider/model).");
        String provider = resolved.provider();
        String model = resolved.model();

        if (apiKey == null || apiKey.isBlank()) {
            if (provider != null && !provider.isBlank())
                apiKey = settings.resolveApiKey(provider);
            if (apiKey == null || (apiKey.isBlank() && SettingsManager.requiresApiKey(provider))) {
                if (parsedArgs.provider() == null) {
                    String detected = settings.detectProviderFromEnv();
                    if (detected != null) {
                        provider = detected;
                        apiKey = settings.resolveApiKey(detected);
                        // Switching provider must switch the model too — otherwise we'd run the detected provider
                        // with the previous provider's model (e.g. openai-codex with "gpt-4o").
                        if (!ModelRegistry.isKnownProviderModel(detected, model))
                            model = ModelRegistry.defaultModelForProvider(detected);
                    }
                }
            }
        }
        if (apiKey == null || (apiKey.isBlank() && SettingsManager.requiresApiKey(provider))) {
            if (!parsedArgs.agent()) {
                throw new IllegalStateException("No API key. Set " + settings.apiKeyHint(provider) + ".");
            }
        }

        // Pre-flight typo check: an explicitly chosen model that isn't in a catalogued provider's list is usually a
        // typo. Suggest the closest known model up front instead of letting the provider API reject it after a wasted
        // round-trip. Non-fatal — new/uncatalogued models still pass straight through.
        if (parsedArgs.model() != null && !ModelRegistry.isKnownProviderModel(provider, model)
                                        && !ModelRegistry.forProvider(provider).isEmpty()) {
            String chosenProvider = provider;
            String chosenModel = model;
            ModelRegistry.closestModel(chosenProvider, chosenModel).ifPresent(suggestion -> CliErrorReporter.warn("'"
                                            + chosenModel + "' is not a known " + chosenProvider
                                            + " model — did you mean '" + suggestion + "'? Proceeding anyway."));
        }

        var sessions = new SessionManager(settings.sessionDir());
        var tools = parsedArgs.noTools() ? new ToolRegistry() : ToolRegistry.createDefault();
        var bashTool = new BashTool(settings.effectiveShellPath(), settings.config().shellCommandPrefix());
        tools.register(bashTool);

        var llm = new LlmService(provider, model, settings::resolveApiKey);
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
        ext.loadServiceLoaderExtensions();
        if (!parsedArgs.noExtensions()) {
            // Pi-compatible extension dirs (matches the .pi/agents and .pi/chains convention), not ~/.pijota.
            ext.loadFromDirectory(Path.of(System.getProperty("user.home"), ".pi", "extensions"));
            // Project-local extensions are executable code from the cwd — only load them from a TRUSTED folder, so a
            // cloned/untrusted repo can't auto-run code on startup. Trust with /trust (takes effect next launch).
            if (new TrustStore().isTrusted(cwd))
                ext.loadFromDirectory(Path.of(cwd, ".pi", "extensions"));
            else if (Path.of(cwd, ".pi", "extensions").toFile().isDirectory())
                logger.info("Skipped untrusted project extensions in {}/.pi/extensions — run /trust to enable them.",
                                                cwd);
        }
        // Resolve extension enablement uniformly: declared default → settings.json "extensions" → CLI --x / --no-x.
        // Fold the legacy typed --no-* flags into the SAME resolution so they keep working as aliases (the --tools
        // registry switch and --no-extensions discovery toggle are separate axes and stay above).
        var flags = new java.util.LinkedHashMap<>(parsedArgs.extensionFlags());
        if (parsedArgs.noSkills())
            flags.put("no-skills", "true");
        if (parsedArgs.noPromptTemplates())
            flags.put("no-prompt-templates", "true");
        if (parsedArgs.noContextFiles() || !settings.contextFilesEnabled())
            flags.put("no-context-files", "true"); // also honors the legacy "context_files" setting
        ext.setExtensionFlags(flags);
        ext.setExtensionsConfig(settings.config().extensions());

        // Niche tool, opt-in (--whoami) — gated through the same resolution as extensions.
        if (ext.isEnabled("whoami", false))
            tools.register(new WhoamiTool());

        // Every extension is registered unconditionally; ExtensionRegistry.register() gates each via isEnabled() on the
        // declared default + settings + flags. (TranscendenceExtension is also JVM-only via its unavailableReason.)
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
        ext.register(new PromptTemplateExtension(parsedArgs.promptTemplatePaths())); // --no-prompt-templates disables
        ext.register(new ContextFilesExtension(cwd)); // --no-context-files / context_files setting disables

        if (!parsedArgs.enabledTools().isEmpty())
            tools.enableOnly(parsedArgs.enabledTools());

        String appendPrompt = parsedArgs.appendSystemPrompts().isEmpty()
                                        ? null
                                        : String.join("\n", parsedArgs.appendSystemPrompts());
        String systemPrompt = SystemPrompt.build(parsedArgs.systemPrompt(), appendPrompt, cwd,
                                        ext.systemPromptContributions());
        ext.setSystemPrompt(systemPrompt);

        int contextWindow = (int) ModelRegistry.resolveContextWindow(provider, model);

        var session = new PijotaSession(llm, tools, sessions, ext, systemPrompt, cwd, parsedArgs.noSession(),
                                        contextWindow, settings.config().compaction(), settings.maxTurns());
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

        // One-line record of what the agent actually started with — the first thing to check when diagnosing "why did
        // it behave like that": resolved provider/model, enabled tool count, and cwd.
        logger.info("bootstrap ready · {}/{} · {} tool(s) · cwd={}", provider, model, tools.enabledToolNames().size(),
                                        cwd);

        return new Result(llm, tools, ext, session, sessions, settings, parsedArgs, cwd);
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
