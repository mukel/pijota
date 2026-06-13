package ai.pijota.extensions.mcp;

import java.util.List;
import java.util.Map;

/**
 * A resolved MCP server definition: how to launch it (stdio transport) and the environment it needs. Produced by
 * {@link McpConfig} from {@code mcp.json}, with {@code ${VAR}} placeholders already expanded.
 *
 * @param name
 *            logical server name (the JSON key); used to namespace its tools as {@code mcp__<name>__<tool>}
 * @param command
 *            executable to run (e.g. {@code npx})
 * @param args
 *            command arguments
 * @param env
 *            extra environment variables for the child process
 * @param enabled
 *            whether to connect this server at startup
 */
public record McpServerConfig(String name, String command, List<String> args, Map<String, String> env,
                                boolean enabled) {

    public McpServerConfig {
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
    }
}
