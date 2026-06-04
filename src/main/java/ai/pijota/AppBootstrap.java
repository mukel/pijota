package ai.pijota;

import ai.pijota.agent.PijotaSession;
import ai.pijota.agent.SessionManager;
import ai.pijota.agent.SystemPrompt;
import ai.pijota.cli.Args;
import ai.pijota.config.SettingsManager;
import ai.pijota.extensions.ExtensionRegistry;
import ai.pijota.extensions.plan.PlanExtension;
import ai.pijota.extensions.task.TaskExtension;
import ai.pijota.llm.LlmService;
import ai.pijota.llm.ModelRegistry;
import ai.pijota.tools.BashTool;
import ai.pijota.tools.ToolRegistry;
import ai.pijota.util.ProcessRunner;
import ai.pijota.util.Util;

import ai.pijota.util.SelfRespawn;

import java.io.IOException;
import java.nio.file.Path;

final class AppBootstrap {
    record Result(
        LlmService llm,
        ToolRegistry tools,
        ExtensionRegistry extensions,
        PijotaSession session,
        SessionManager sessions,
        SettingsManager settings,
        Args args,
        String cwd
    ) {}

    static Result bootstrap(Args parsedArgs) throws Exception {
        var settings = new SettingsManager();
        String cwd = System.getProperty("user.dir");

        String provider = parsedArgs.provider();
        String model = parsedArgs.model();
        String apiKey = parsedArgs.apiKey();

        if (provider == null) provider = settings.config().defaultProvider();
        if (provider == null && model != null) provider = ModelRegistry.providerForModel(model);
        if (apiKey == null || apiKey.isBlank()) {
            if (provider != null && !provider.isBlank()) apiKey = settings.resolveApiKey(provider);
            if (apiKey == null || apiKey.isBlank()) {
                if (parsedArgs.provider() == null) {
                    String detected = settings.detectProviderFromEnv();
                    if (detected != null) {
                        provider = detected;
                        apiKey = settings.resolveApiKey(provider);
                    }
                }
            }
        }
        if (provider == null) provider = "openai";
        if (model == null) {
            model = Util.coalesce(settings.config().defaultModel(),
                ModelRegistry.defaultModelForProvider(provider));
        }
        if (apiKey == null || apiKey.isBlank()) {
            if (!parsedArgs.agent()) {
                System.err.println("No API key. Set " + settings.apiKeyHint(provider) + ".");
                System.exit(1);
            }
        }

        var tools = parsedArgs.noTools() ? new ToolRegistry() : ToolRegistry.createDefault();
        if (!parsedArgs.enabledTools().isEmpty()) tools.enableOnly(parsedArgs.enabledTools());
        var bashTool = new BashTool(
            settings.effectiveShellPath(), settings.config().shellCommandPrefix());
        tools.register(bashTool);

        var llm = new LlmService(provider, model, settings::resolveApiKey);

        var ext = new ExtensionRegistry();
        llm.setModelChangeCallback((oldModel, newModel) ->
            ext.fireModelSelect(newModel, oldModel, "set"));
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
        ext.setExtensionFlags(parsedArgs.extensionFlags());
        if (!parsedArgs.noSkills())
            ext.register(new ai.pijota.extensions.skills.SkillExtension(parsedArgs.skillPaths()));

        if (!parsedArgs.noContextFiles() && settings.contextFilesEnabled())
            ext.register(new ai.pijota.extensions.examples.ContextFilesExtension(cwd));

        String appendPrompt = parsedArgs.appendSystemPrompts().isEmpty() ? null
            : String.join("\n", parsedArgs.appendSystemPrompts());
        String systemPrompt = SystemPrompt.build(tools, parsedArgs.systemPrompt(),
            appendPrompt, cwd, ext.systemPromptContributions());
        ext.setSystemPrompt(systemPrompt);

        var sessions = new SessionManager(settings.sessionDir());
        String finalProvider = provider;
        String finalModel = model;
        int contextWindow = (int) ModelRegistry.KNOWN_MODELS.stream()
            .filter(m -> m.provider().equals(finalProvider) && m.id().equals(finalModel))
            .mapToLong(m -> m.contextWindow())
            .findFirst()
            .orElse(128_000L);

        var session = new PijotaSession(llm, tools, sessions, ext,
            systemPrompt, cwd, parsedArgs.noSession(), contextWindow, settings.config().compaction());
        try {
            if (parsedArgs.sessionPath() != null) {
                session.load(parsedArgs.sessionPath());
                restoreSessionModel(session, settings);
            } else if (parsedArgs.recoverSession()) {
                Path recovery = SelfRespawn.readRecoverySession();
                if (recovery != null) {
                    session.load(recovery);
                    restoreSessionModel(session, settings);
                    SelfRespawn.clearRecoveryFile();
                } else {
                    System.err.println("No recovery session found — starting fresh.");
                }
            } else if (parsedArgs.continueSession()) {
                session.continueLastSession();
                restoreSessionModel(session, settings);
            }
        } catch (IOException e) {
            System.err.println("[transcend] failed to load session: " + e.getMessage() + " — starting fresh.");
        }
        ext.setAgentLoop(session.agentLoop());
        ext.fire(new ai.pijota.extensions.ExtensionEvent.SessionStart(session.sessionFile()));
        mergeTranscendWorktree(parsedArgs);

        return new Result(llm, tools, ext, session, sessions, settings, parsedArgs, cwd);
    }

    private static void mergeTranscendWorktree(Args args) {
        String branch = args.extensionFlags().get("transcend-merge-branch");
        String worktree = args.extensionFlags().get("transcend-worktree");
        if ((branch == null || branch.isBlank()) && (worktree == null || worktree.isBlank())) return;

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
                if (!applyWorktreeDiff(Path.of(worktree.strip()))) return;

                ProcessRunner.command("git", "worktree", "remove", "--force", worktree.strip())
                    .redirectErrorStream(true)
                    .run();
            }
        } catch (Exception e) {
            System.err.println("[transcend] automatic merge failed: " + e.getMessage());
        }
    }

    private static void restoreSessionModel(PijotaSession session, SettingsManager settings) throws IOException {
        var selection = session.latestModelSelection();
        if (selection == null) return;
        String key = settings.resolveApiKey(selection.provider());
        if (key == null || key.isBlank()) {
            System.err.println("[session] cannot restore model " + selection.provider() + "/" + selection.model()
                + " — missing API key");
            return;
        }
        long contextWindow = selection.contextWindow() > 0 ? selection.contextWindow()
            : ModelRegistry.KNOWN_MODELS.stream()
                .filter(m -> m.provider().equalsIgnoreCase(selection.provider())
                    && m.id().equalsIgnoreCase(selection.model()))
                .mapToLong(m -> m.contextWindow())
                .findFirst()
                .orElse(128_000L);
        session.restoreModel(selection.provider(), selection.model(), key, contextWindow);
    }

    private static boolean applyWorktreeDiff(Path worktree) throws IOException {
        var diff = ProcessRunner.command("git", "diff", "--binary", "HEAD")
            .workDir(worktree)
            .redirectErrorStream(true)
            .maxOutputBytes(10 * 1024 * 1024)
            .run();
        if (diff.exitCode() != 0) {
            System.err.println("[transcend] failed to collect worktree diff:");
            System.err.println(diff.stdout().strip());
            return false;
        }
        if (diff.stdout().isBlank()) return true;

        var apply = ProcessRunner.command("git", "apply", "--3way", "--whitespace=nowarn")
            .feedStdin(diff.stdout())
            .redirectErrorStream(true)
            .maxOutputBytes(10 * 1024 * 1024)
            .run();
        if (apply.exitCode() != 0) {
            System.err.println("[transcend] failed to apply worktree changes:");
            System.err.println(apply.stdout().strip());
            return false;
        }
        return true;
    }
}
