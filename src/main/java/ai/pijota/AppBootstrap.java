package ai.pijota;

import ai.pijota.agent.PijotaSession;
import ai.pijota.agent.SessionManager;
import ai.pijota.agent.SystemPrompt;
import ai.pijota.cli.Args;
import ai.pijota.config.SettingsManager;
import ai.pijota.extensions.ExtensionRegistry;
import ai.pijota.extensions.plan.PlanExtension;
import ai.pijota.extensions.system.SystemPromptExtension;
import ai.pijota.extensions.task.TaskExtension;
import ai.pijota.llm.LlmService;
import ai.pijota.llm.ModelRegistry;
import ai.pijota.tools.BashTool;
import ai.pijota.tools.ToolRegistry;
import ai.pijota.util.Util;

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

        var llm = new LlmService(provider, model, apiKey);

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
        ext.register(new SystemPromptExtension());
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
        if (parsedArgs.sessionPath() != null) {
            session.load(parsedArgs.sessionPath());
        } else if (parsedArgs.continueSession()) {
            session.continueLastSession();
        }
        ext.setAgentLoop(session.agentLoop());
        ext.fire(new ai.pijota.extensions.ExtensionEvent.SessionStart(session.sessionFile()));

        return new Result(llm, tools, ext, session, sessions, settings, parsedArgs, cwd);
    }
}
