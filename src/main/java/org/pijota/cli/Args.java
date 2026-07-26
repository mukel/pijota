package org.pijota.cli;

import java.util.List;
import java.util.Map;

public record Args(String provider, String model, String apiKey, String systemPrompt, List<String> appendSystemPrompts,
                                String thinkingLevel, OutputMode outputMode, String sessionPath, boolean noSession,
                                boolean noTools, boolean noBuiltinTools, boolean readOnly, boolean noExtensions,
                                boolean noBuiltinExtensions, boolean noSkills, boolean noThemes, boolean noContextFiles,
                                boolean noPromptTemplates, List<String> enabledTools, List<String> excludeTools,
                                List<String> extensionPaths, List<String> skillPaths, List<String> themePaths,
                                List<String> promptTemplatePaths, boolean print, boolean resume,
                                boolean continueSession, boolean recoverSession, String forkPath, String exportPath,
                                boolean listModels, String listModelsFilter, boolean approveProject,
                                boolean noApproveProject, boolean help, boolean version, boolean verbose, boolean agent,
                                boolean worker, List<String> fileArgs, List<String> promptArgs,
                                Map<String, String> extensionFlags, Integer maxTurns) {
    public enum OutputMode {
        TEXT, JSON, RPC
    }
}
