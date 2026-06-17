package ai.pijota;

import ai.pijota.agent.PijotaSession;
import ai.pijota.agent.SessionManager;
import ai.pijota.agent.SystemPrompt;
import ai.pijota.cli.Args;
import ai.pijota.config.SettingsManager;
import ai.pijota.extensions.ExtensionRegistry;
import ai.pijota.extensions.ExtensionRuntime;
import ai.pijota.extensions.plan.PlanExtension;
import ai.pijota.extensions.task.TaskExtension;
import ai.pijota.llm.LlmService;
import ai.pijota.llm.ModelRegistry;
import ai.pijota.tools.BashTool;
import ai.pijota.tools.ToolRegistry;
import ai.pijota.tools.TranscendenceTool;
import ai.pijota.tools.WhoamiTool;
import ai.pijota.util.ProcessRunner;

import ai.pijota.util.SelfRespawn;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

final class AppBootstrap {
    record Result(LlmService llm, ToolRegistry tools, ExtensionRegistry extensions, PijotaSession session,
                                    SessionManager sessions, SettingsManager settings, Args args, String cwd) {
    }

    static Result bootstrap(Args parsedArgs) throws Exception {
        return bootstrap(parsedArgs, new SettingsManager());
    }

    // package-visible for tests so callers can inject a SettingsManager backed by a temp directory.
    static Result bootstrap(Args parsedArgs, SettingsManager settings) throws Exception {
        String cwd = System.getProperty("user.dir");

        String apiKey = parsedArgs.apiKey();

        var resolved = ModelRegistry.resolveProviderModel(parsedArgs.provider(), parsedArgs.model(), settings);
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
                    }
                }
            }
        }
        if (apiKey == null || (apiKey.isBlank() && SettingsManager.requiresApiKey(provider))) {
            if (!parsedArgs.agent()) {
                System.err.println("No API key. Set " + settings.apiKeyHint(provider) + ".");
                System.exit(1);
            }
        }

        var sessions = new SessionManager(settings.sessionDir());
        var tools = parsedArgs.noTools() ? new ToolRegistry() : ToolRegistry.createDefault();
        var bashTool = new BashTool(settings.effectiveShellPath(), settings.config().shellCommandPrefix());
        tools.register(bashTool);
        // Niche tools, opt-in to keep the default prompt lean: whoami (--whoami).
        var flags = parsedArgs.extensionFlags();
        if (flagOn(flags, "whoami"))
            tools.register(new WhoamiTool());
        // Transcendence (self-modification) is a built-in, on by default; disable with --no-transcendence.
        boolean transcendence = !flagOn(flags, "no-transcendence");
        if (transcendence)
            tools.register(new TranscendenceTool());

        var llm = new LlmService(provider, model, settings::resolveApiKey);
        String effectiveThinking = parsedArgs.thinkingLevel();
        if (effectiveThinking == null || effectiveThinking.isBlank()) {
            effectiveThinking = settings.config().thinkingLevel();
        }
        if (effectiveThinking != null && !effectiveThinking.isBlank()) {
            llm.setThinkingLevel(ai.pijota.llm.ThinkingLevel.fromString(effectiveThinking));
        }

        var ext = new ExtensionRegistry();
        llm.setModelChangeCallback((oldModel, newModel) -> ext.fireModelSelect(newModel, oldModel, "set"));
        ext.setToolRegistry(tools);
        ext.setCwd(Path.of(cwd));
        ext.setLlm(llm);
        ext.loadServiceLoaderExtensions();
        if (!parsedArgs.noExtensions()) {
            ext.loadFromDirectory(Path.of(System.getProperty("user.home"), ".pi", "extensions"));
            ext.loadFromDirectory(Path.of(cwd, ".pi", "extensions"));
        }
        ext.register(new TaskExtension());
        ext.register(new PlanExtension());
        ext.register(new ai.pijota.extensions.examples.InlineBashExtension());
        ext.register(new ai.pijota.extensions.examples.FilterOutputExtension());
        ext.register(new ai.pijota.extensions.examples.SecurityExtension());
        ext.register(new ai.pijota.extensions.examples.WhimsicalExtension());
        ext.register(new ai.pijota.extensions.examples.GoalExtension());
        ext.register(new ai.pijota.extensions.examples.ScheduleExtension());
        ext.register(new ai.pijota.extensions.mcp.McpExtension());
        ext.setExtensionFlags(parsedArgs.extensionFlags());
        ext.register(new ai.pijota.extensions.examples.TodoExtension()); // opt-in (--todo); registered after flags
        ext.register(new ai.pijota.extensions.examples.TokenSpeedExtension()); // opt-in (--token-speed)
        ext.register(new ai.pijota.extensions.examples.WebBrowseExtension()); // opt-in (--browse)
        ext.register(new ai.pijota.extensions.examples.BtwExtension()); // /btw side-conversation (slash commands only)
        if (!parsedArgs.noSkills())
            ext.register(new ai.pijota.extensions.skills.SkillExtension(parsedArgs.skillPaths()));

        if (!parsedArgs.noPromptTemplates())
            ext.register(new ai.pijota.extensions.prompts.PromptTemplateExtension(parsedArgs.promptTemplatePaths()));

        if (!parsedArgs.noContextFiles() && settings.contextFilesEnabled())
            ext.register(new ai.pijota.extensions.examples.ContextFilesExtension(cwd));

        if (!parsedArgs.enabledTools().isEmpty())
            tools.enableOnly(parsedArgs.enabledTools());

        String appendPrompt = parsedArgs.appendSystemPrompts().isEmpty()
                                        ? null
                                        : String.join("\n", parsedArgs.appendSystemPrompts());
        String systemPrompt = SystemPrompt.build(parsedArgs.systemPrompt(), appendPrompt, cwd,
                                        ext.systemPromptContributions(), transcendence);
        ext.setSystemPrompt(systemPrompt);

        int contextWindow = (int) ModelRegistry.resolveContextWindow(provider, model);

        var session = new PijotaSession(llm, tools, sessions, ext, systemPrompt, cwd, parsedArgs.noSession(),
                                        contextWindow, settings.config().compaction());
        ext.setAgentLoop(session.agentLoop());
        // Generic built-in commands (e.g. /model) usable by every frontend — registered once the session exists.
        ext.register(new ai.pijota.extensions.examples.CoreCommandsExtension(session, settings));
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
                    System.err.println("No recovery session found — starting fresh.");
                }
            } else if (parsedArgs.continueSession()) {
                session.continueLastSession();
                restoreSessionModel(session, settings);
                restoreSessionThinkingLevel(session);
                loadedSession = true;
            }
        } catch (IOException e) {
            System.err.println("[transcend] failed to load session: " + e.getMessage() + " — starting fresh.");
        }
        ext.fire(new ai.pijota.extensions.ExtensionEvent.SessionStart(session.sessionFile()));
        if (loadedSession) {
            session.checkCacheTarget();
        } else {
            session.refreshCacheMetadata();
            session.recordInitialModelState();
        }
        mergeTranscendWorktree(parsedArgs);

        return new Result(llm, tools, ext, session, sessions, settings, parsedArgs, cwd);
    }

    /** Whether an opt-in extension flag is truthy (true/1/yes). */
    private static boolean flagOn(Map<String, String> flags, String name) {
        return ExtensionRuntime.truthy(flags.get(name));
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
                    System.err.println("[transcend] automatic merge failed:");
                    System.err.println(merge.stdout().strip());
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
            System.err.println("[transcend] automatic merge failed: " + e.getMessage());
        }
    }

    private static void restoreSessionModel(PijotaSession session, SettingsManager settings) throws IOException {
        var selection = session.latestModelSelection();
        if (selection == null)
            return;
        var resolved = ModelRegistry.resolve(selection, settings);
        if (resolved == null) {
            System.err.println("[session] cannot restore model " + selection.provider() + "/" + selection.model()
                                            + " — missing API key");
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
            System.err.println("[transcend] failed to collect worktree diff:");
            System.err.println(diff.stdout().strip());
            return false;
        }
        if (diff.stdout().isBlank())
            return true;

        var apply = ProcessRunner.command("git", "apply", "--3way", "--whitespace=nowarn").feedStdin(diff.stdout())
                                        .redirectErrorStream(true).maxOutputBytes(10 * 1024 * 1024).run();
        if (apply.exitCode() != 0) {
            System.err.println("[transcend] failed to apply worktree changes:");
            System.err.println(apply.stdout().strip());
            return false;
        }
        return true;
    }
}
