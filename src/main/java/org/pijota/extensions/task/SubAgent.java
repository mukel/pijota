package org.pijota.extensions.task;

import org.pijota.util.PijotaHome;
import org.pijota.util.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Sub-agent configuration matching Pi's agents.md format; loaded from markdown/JSON across user and project paths by
 * {@link #loadAll}.
 */
public record SubAgent(String name, String description, String systemPrompt, List<String> tools, String model,
                                String source) {
    private static final Logger logger = LoggerFactory.getLogger(SubAgent.class);
    /**
     * The sub-agents shipped in resources ({@code src/main/resources/agents}), loaded by name. The classpath
     * {@code agents} directory can't be enumerated in a native image, so this list is the authority.
     */
    private static final List<String> BUILTIN_AGENTS = List.of("explore", "summarize");

    public static SubAgent loadMarkdown(Path file, String source) {
        String name = file.getFileName().toString().replace(".md", "");
        try {
            return parseMarkdown(Files.readString(file), name, source);
        } catch (Exception e) {
            return new SubAgent(name, "failed: " + e.getMessage(), "", List.of(), null, source);
        }
    }

    /** Parses an agent's markdown body: optional YAML frontmatter (name, description, model, tools) then the prompt. */
    private static SubAgent parseMarkdown(String content, String defaultName, String source) {
        String name = defaultName;
        String description = "";
        String systemPrompt = content;
        String model = null;
        List<String> tools = List.of();
        if (content.startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end > 0) {
                String yaml = content.substring(3, end);
                systemPrompt = content.substring(end + 3).strip();
                for (String kv : yaml.split("\\R")) {
                    int colon = kv.indexOf(':');
                    if (colon < 0)
                        continue;
                    String key = kv.substring(0, colon).strip();
                    String value = kv.substring(colon + 1).strip();
                    switch (key) {
                        case "name" -> name = value;
                        case "description" -> description = value;
                        // YAML null literals mean "no override"; without this, `model: null` becomes the literal
                        // string "null" and is sent as the model (e.g. Codex rejects "the 'null' model").
                        case "model" -> model = yamlNull(value);
                        case "tools" -> tools = Arrays.stream(value.split("[,;]")).map(String::strip)
                                                        .filter(s -> !s.isBlank()).toList();
                    }
                }
            }
        }
        return new SubAgent(name, description, systemPrompt, tools, model, source);
    }

    /** Maps YAML's null literals ({@code null}, {@code ~}, empty) to Java null; other values pass through unchanged. */
    private static String yamlNull(String value) {
        return value.isBlank() || value.equals("null") || value.equals("~") ? null : value;
    }

    public static SubAgent loadJson(Path file, String source) {
        try {
            @SuppressWarnings("unchecked")
            var map = Util.MAPPER.readValue(file.toFile(), Map.class);
            String name = string(map, "name", file.getFileName().toString().replace(".json", ""));
            return new SubAgent(name, string(map, "description", ""), string(map, "systemPrompt", ""),
                                            map.containsKey("tools") ? toolsFromMap(map.get("tools")) : List.of(),
                                            string(map, "model", null), source);
        } catch (Exception e) {
            return new SubAgent(file.getFileName().toString().replace(".json", ""), "failed: " + e.getMessage(), "",
                                            List.of(), null, source);
        }
    }

    @SuppressWarnings("unchecked")
    static List<String> toolsFromMap(Object val) {
        if (val instanceof List<?> list)
            return list.stream().map(Object::toString).toList();
        if (val instanceof String s)
            return Arrays.stream(s.split("[,;]")).map(String::strip).filter(t -> !t.isBlank()).toList();
        return List.of();
    }

    static String string(Map<String, Object> m, String key, String defaultVal) {
        Object v = m.get(key);
        return v instanceof String s && !s.isBlank() ? s : defaultVal;
    }

    public static List<SubAgent> loadAll(Path cwd) {
        var agents = new ArrayList<SubAgent>();

        agents.addAll(loadBuiltinAgents());
        Path userDir = Path.of(System.getProperty("user.home"), ".pi", "agent", "agents");
        agents.addAll(loadMarkdownDir(userDir, "user"));

        Path projectDir = cwd.resolve(".pi/agents");
        agents.addAll(loadMarkdownDir(projectDir, "project"));

        // Pi legacy paths
        Path userAgents = Path.of(System.getProperty("user.home"), ".agents");
        agents.addAll(loadMarkdownDir(userAgents, "user"));

        Path projectAgents = cwd.resolve(".agents");
        agents.addAll(loadMarkdownDir(projectAgents, "project"));

        // Legacy Pijota paths
        Path pijotaUser = PijotaHome.resolve("agents");
        agents.addAll(loadJsonDir(pijotaUser, "user"));

        Path pijotaProject = cwd.resolve(".pijota/agents");
        agents.addAll(loadJsonDir(pijotaProject, "project"));

        // Apply overrides from config files (later paths win)
        applyOverrides(agents, PijotaHome.resolve("subagents.json"));
        applyOverrides(agents, cwd.resolve(".pijota/subagents.json"));

        return agents;
    }

    @SuppressWarnings("unchecked")
    private static void applyOverrides(List<SubAgent> agents, Path configFile) {
        try {
            if (!Files.exists(configFile))
                return;
            Map<String, Object> config = Util.MAPPER.readValue(configFile.toFile(), Map.class);
            Map<String, Object> overrides = (Map<String, Object>) config.get("agentOverrides");
            if (overrides == null)
                return;
            boolean disableBuiltins = Boolean.TRUE.equals(config.get("disableBuiltins"));

            for (int i = agents.size() - 1; i >= 0; i--) {
                var agent = agents.get(i);
                var override = (Map<String, Object>) overrides.get(agent.name());
                if (override != null && Boolean.TRUE.equals(override.get("disabled"))) {
                    agents.remove(i);
                    continue;
                }
                if (disableBuiltins && "user".equals(agent.source())) {
                    agents.remove(i);
                    continue;
                }
                if (override != null) {
                    agents.set(i, agent.withOverride(override));
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load sub-agent overrides from {}: {}", configFile, e.getMessage());
        }
    }

    private SubAgent withOverride(Map<String, Object> override) {
        String m = override.get("model") instanceof String s ? s : model;
        List<String> t = override.containsKey("tools") ? toolsFromMap(override.get("tools")) : tools;
        String sp = override.get("systemPrompt") instanceof String s ? s : systemPrompt;
        return new SubAgent(name, description, sp, t, m, source);
    }

    private static List<SubAgent> loadMarkdownDir(Path dir, String source) {
        if (!Files.isDirectory(dir))
            return List.of();
        try (var stream = Files.walk(dir, 3)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".md")).map(p -> loadMarkdown(p, source))
                                            .toList();
        } catch (Exception ignored) {}
        return List.of();
    }

    private static List<SubAgent> loadJsonDir(Path dir, String source) {
        if (!Files.isDirectory(dir))
            return List.of();
        try (var stream = Files.newDirectoryStream(dir, "*.json")) {
            var list = new ArrayList<SubAgent>();
            for (Path p : stream)
                list.add(loadJson(p, source));
            return list;
        } catch (Exception ignored) {}
        return List.of();
    }

    private static List<SubAgent> loadBuiltinAgents() {
        var agents = new ArrayList<SubAgent>();
        for (String name : BUILTIN_AGENTS) {
            try (var in = SubAgent.class.getResourceAsStream("/agents/" + name + ".md")) {
                if (in != null)
                    agents.add(parseMarkdown(new String(in.readAllBytes(), StandardCharsets.UTF_8), name, "builtin"));
            } catch (Exception ignored) {}
        }
        return agents;
    }
}
