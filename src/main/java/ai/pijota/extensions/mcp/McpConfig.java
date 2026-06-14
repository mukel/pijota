package ai.pijota.extensions.mcp;

import ai.pijota.util.PijotaHome;
import ai.pijota.util.Util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads MCP server definitions from {@code mcp.json}, merging the global file ({@code ~/.pijota/mcp.json}) with the
 * per-project file ({@code <cwd>/.pijota/mcp.json}); project entries override global ones with the same name.
 *
 * <p>
 * String values in {@code command}, {@code args} and {@code env} may contain {@code ${VAR}} placeholders, expanded from
 * the parent process environment (so secrets stay out of the file). A missing variable expands to the empty string. The
 * file shape is {@code {"servers": {"<name>": {"command", "args", "env", "enabled"}}}}.
 */
final class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);
    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private McpConfig() {}

    /** Loads and merges the global ({@code ~/.pijota/mcp.json}) and project configs for {@code cwd}. */
    static List<McpServerConfig> load(Path cwd) {
        Path global = PijotaHome.resolve("mcp.json");
        Path project = cwd == null ? null : cwd.resolve(".pijota").resolve("mcp.json");
        return load(global, project, System::getenv);
    }

    /** Testable variant: explicit file paths and a {@code ${VAR}} resolver; returns only enabled servers. */
    static List<McpServerConfig> load(Path globalFile, Path projectFile, Function<String, String> env) {
        var merged = new LinkedHashMap<String, RawServer>();
        readFile(globalFile, merged);
        readFile(projectFile, merged);

        var out = new ArrayList<McpServerConfig>();
        for (var entry : merged.entrySet()) {
            RawServer raw = entry.getValue();
            if (raw == null || raw.command == null || raw.command.isBlank())
                continue;
            boolean enabled = raw.enabled == null || raw.enabled;
            if (!enabled)
                continue;
            out.add(new McpServerConfig(entry.getKey(), expand(raw.command, env), expandAll(raw.args, env),
                                            expandValues(raw.env, env), true));
        }
        return out;
    }

    private static void readFile(Path file, Map<String, RawServer> into) {
        if (file == null || !Files.isRegularFile(file))
            return;
        try {
            RawConfig cfg = Util.MAPPER.readValue(file.toFile(), RawConfig.class);
            if (cfg != null && cfg.servers != null)
                into.putAll(cfg.servers);
        } catch (Exception e) {
            log.warn("Ignoring unreadable MCP config {}: {}", file, e.getMessage());
        }
    }

    private static String expand(String value, Function<String, String> env) {
        if (value == null || value.indexOf('$') < 0)
            return value;
        Matcher m = VAR.matcher(value);
        var sb = new StringBuilder();
        while (m.find()) {
            String resolved = env.apply(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(resolved == null ? "" : resolved));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static List<String> expandAll(List<String> values, Function<String, String> env) {
        if (values == null)
            return List.of();
        var out = new ArrayList<String>(values.size());
        for (String v : values)
            out.add(expand(v, env));
        return out;
    }

    private static Map<String, String> expandValues(Map<String, String> values, Function<String, String> env) {
        if (values == null)
            return Map.of();
        var out = new LinkedHashMap<String, String>();
        values.forEach((k, v) -> out.put(k, expand(v, env)));
        return out;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawConfig(Map<String, RawServer> servers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawServer(String command, List<String> args, Map<String, String> env, Boolean enabled) {
    }
}
