package org.pijota.cli;

import org.pijota.cli.Args.OutputMode;
import org.pijota.extensions.BuiltinExtensions;
import org.pijota.llm.ModelRegistry;
import org.pijota.llm.ThinkingLevel;
import org.pijota.util.Cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ArgParser {

    /** Frontend subcommands: `pijota web|telegram|acp`, the positional twins of the {@code --web}/… flags. */
    /** Also the set that {@link org.pijota.extensions.ExtensionRegistry} must not report as unknown flags. */
    public static final Set<String> FRONTEND_SUBCOMMANDS = Set.of("web", "telegram", "acp", "tui");

    public static Args parse(String[] rawArgs) {
        var provider = (String) null;
        var model = (String) null;
        var apiKey = (String) null;
        var systemPrompt = (String) null;
        var appendSystemPrompts = new ArrayList<String>();
        var thinkingLevel = (String) null;
        var maxTurns = (Integer) null;
        var outputMode = OutputMode.TEXT;
        var sessionPath = (String) null;
        var noSession = false;
        var noTools = false;
        var noBuiltinTools = false;
        var readOnly = false;
        var noExtensions = false;
        var noBuiltinExtensions = false;
        var noSkills = false;
        var noThemes = false;
        var noContextFiles = false;
        var noPromptTemplates = false;
        var enabledTools = new ArrayList<String>();
        var excludeTools = new ArrayList<String>();
        var extensionPaths = new ArrayList<String>();
        var skillPaths = new ArrayList<String>();
        var themePaths = new ArrayList<String>();
        var promptTemplatePaths = new ArrayList<String>();
        var print = false;
        var resume = false;
        var continueSession = false;
        var recoverSession = false;
        var forkPath = (String) null;
        var exportPath = (String) null;
        var listModels = false;
        var listModelsFilter = (String) null;
        var approveProject = false;
        var noApproveProject = false;
        var help = false;
        var version = false;
        var license = false;
        var verbose = false;
        var agent = false;
        var worker = false;
        var fileArgs = new ArrayList<String>();
        var promptArgs = new ArrayList<String>();
        var extensionFlags = new LinkedHashMap<String, String>();

        var cursor = new Cursor(rawArgs);
        while (cursor.hasNext()) {
            String arg = cursor.next();
            switch (arg) {
                case "--provider" -> provider = cursor.value(arg);
                case "--model", "-m" -> {
                    var pm = ModelRegistry.parseProviderModel(cursor.value(arg));
                    if (pm.provider() != null)
                        provider = pm.provider();
                    model = pm.model();
                }
                case "--api-key" -> apiKey = cursor.value(arg);
                case "--system-prompt" -> systemPrompt = cursor.value(arg);
                case "--append-system-prompt" -> appendSystemPrompts.add(cursor.value(arg));
                case "--thinking" -> thinkingLevel = checkThinking(cursor.value(arg));
                case "--max-turns" -> maxTurns = parseMaxTurns(cursor.value(arg));
                case "--mode" -> outputMode = parseMode(cursor.value(arg));
                case "--session", "-s", "--session-path" -> sessionPath = cursor.value(arg);
                case "--fork" -> forkPath = cursor.value(arg);
                case "--export" -> exportPath = cursor.value(arg);
                case "--tool", "--tools", "-t" -> enabledTools.addAll(parseList(cursor.value(arg)));
                case "--no-tool", "--exclude-tools", "-xt" -> excludeTools.addAll(parseList(cursor.value(arg)));
                case "--extension", "-e" -> extensionPaths.add(cursor.value(arg));
                case "--skill" -> skillPaths.add(cursor.value(arg));
                case "--theme" -> themePaths.add(cursor.value(arg));
                case "--list-models" -> {
                    listModels = true;
                    String filter = cursor.optionalValue();
                    if (filter != null)
                        listModelsFilter = filter;
                }
                case "--print", "-p" -> {
                    print = true;
                    String prompt = cursor.optionalPromptValue();
                    if (prompt != null)
                        promptArgs.add(prompt);
                }
                case "--resume", "-r" -> resume = true;
                case "--continue", "-c" -> continueSession = true;
                case "--recover" -> recoverSession = true;
                case "--no-session" -> noSession = true;
                case "--no-tools", "-nt" -> noTools = true;
                case "--readonly", "--read-only" -> readOnly = true;
                case "--no-builtin-tools", "-nbt" -> noBuiltinTools = true;
                case "--no-extensions", "-ne" -> noExtensions = true;
                case "--no-builtin-extensions", "-nbe" -> noBuiltinExtensions = true;
                case "--no-skills", "-ns" -> noSkills = true;
                case "--no-themes" -> noThemes = true;
                case "--no-context-files", "-nc" -> noContextFiles = true;
                case "--no-prompt-templates", "-np" -> noPromptTemplates = true;
                case "--prompt-template", "--template" -> promptTemplatePaths.add(cursor.value(arg));
                case "--verbose" -> verbose = true;
                case "--approve", "-a" -> approveProject = true;
                case "--no-approve", "-na" -> noApproveProject = true;
                case "--help", "-h" -> help = true;
                case "--version", "-v" -> version = true;
                case "--license", "--licence" -> license = true;
                case "--agent" -> agent = true;
                case "--worker" -> worker = true;
                default -> {
                    if (arg.startsWith("--") && arg.length() > 2) {
                        String name = arg.substring(2);
                        int eq = name.indexOf('=');
                        if (eq > 0) {
                            extensionFlags.put(name.substring(0, eq), name.substring(eq + 1));
                        } else {
                            String value = cursor.optionalValue();
                            extensionFlags.put(name, value != null ? value : "true");
                        }
                    } else if (arg.startsWith("@")) {
                        fileArgs.add(arg.substring(1));
                    } else if (arg.startsWith("-")) {
                        // Unknown single-dash flag, never a valid extension flag (those are --name), so it would
                        // otherwise vanish silently. Warn instead of dropping it.
                        System.err.println("pijota: ignoring unknown option '" + arg + "'");
                    } else if (promptArgs.isEmpty() && FRONTEND_SUBCOMMANDS.contains(arg)) {
                        // Frontend subcommand, positional twin of --web/--telegram/--acp. `tui` is the default, so a
                        // no-op. Only `web` takes a trailing address (`pijota web 0.0.0.0:8080`).
                        if (!arg.equals("tui")) {
                            String value = arg.equals("web") ? cursor.optionalValue() : null;
                            extensionFlags.put(arg, value != null ? value : "true");
                        }
                    } else {
                        promptArgs.add(arg);
                    }
                }
            }
        }

        return new Args(provider, model, apiKey, systemPrompt, appendSystemPrompts, thinkingLevel, outputMode,
                                        sessionPath, noSession, noTools, noBuiltinTools, readOnly, noExtensions,
                                        noBuiltinExtensions, noSkills, noThemes, noContextFiles, noPromptTemplates,
                                        enabledTools, excludeTools, extensionPaths, skillPaths, themePaths,
                                        promptTemplatePaths, print, resume, continueSession, recoverSession, forkPath,
                                        exportPath, listModels, listModelsFilter, approveProject, noApproveProject,
                                        help, version, license, verbose, agent, worker, fileArgs, promptArgs,
                                        Map.copyOf(extensionFlags), maxTurns);
    }

    public static void printHelp() {
        System.out.println("""
                                        %1$s - minimalist Java coding agent

                                        USAGE: %1$s [SUBCOMMAND] [OPTIONS] [PROMPT...]

                                        SUBCOMMANDS (frontends; default is the TUI):
                                          tui                       Interactive terminal UI (default)
                                          web [host:port]           Local web UI
                                          telegram                  Telegram bot (needs TELEGRAM_BOT_TOKEN)
                                          acp                       Agent Client Protocol over stdio (Zed, JetBrains)

                                        OPTIONS:
                                          --provider <name>         LLM provider (openai, anthropic, google, ...)
                                          --model, -m <id>          Model name or provider/id shorthand
                                          --api-key <key>           API key
                                          --system-prompt <text>    Custom system prompt
                                          --append-system-prompt <text>  Append to system prompt (repeatable)
                                          --thinking <level>        off, minimal, low, medium, high, xhigh, max
                                          --max-turns <n>           Max tool-call turns per prompt (0 = unlimited)
                                          --mode <mode>             Output mode: text, json, rpc
                                          --print, -p [prompt]      Non-interactive single-shot mode
                                          --session, -s <id>        Session file path or partial UUID
                                          --continue, -c            Continue last session
                                          --recover                 Resume a session a self-rebuild interrupted
                                          --resume, -r              Interactive session picker
                                          --fork <id>               Fork a session
                                          --no-session              Don't save session
                                          --tool, -t <names>        Enable only these tools (allowlist, any source)
                                          --no-tool, -xt <names>    Disable these tools (denylist, any source)
                                          --no-tools, -nt           Disable all tools (built-in and extension)
                                          --readonly, --read-only   Enable read-only exploration tools only
                                          --no-builtin-tools, -nbt  Disable built-in tools, keep extension tools
                                          --no-extensions, -ne      Disable all extensions (built-in and discovered)
                                          --no-builtin-extensions, -nbe  Disable built-in extensions, keep discovered
                                          --no-skills, -ns          Skip skill auto-discovery
                                          --extension, -e <path>    Load extension
                                          --skill <path>            Load skill
                                          --theme <path|name>       Load/select theme (repeatable; "auto" = terminal)
                                          --no-themes               Skip theme discovery
                                          --no-context-files, -nc   Skip AGENTS.md and CLAUDE.md discovery
                                          --no-prompt-templates, -np Skip prompt template auto-discovery
                                          --prompt-template <path>  Load prompt template (repeatable)
                                          --approve, -a             Trust project-local resources for this run
                                          --no-approve, -na         Ignore project-local resources for this run
                                        """.formatted(Cli.name()));
        System.out.println("""
                                        COMMANDS (before any options):
                                          login [provider] [model]  Sign in to a provider (OAuth, else an API key)
                                          logout [provider]         Forget a provider's stored credential
                                          auth <cmd> --provider <p> Print a credential for another tool (print-api-key,
                                                                    print-bearer-token); see `%1$s auth --help`
                                          worktree <cmd>            Manage git worktrees; see `%1$s worktree --help`
                                        """.formatted(Cli.name()));
        System.out.println(BuiltinExtensions.helpSection());
        System.out.println();
        System.out.println("""
                                        MISC:
                                          --export <file>           Export session to Markdown
                                          --list-models [filter]    List available models
                                          --verbose                 Debug logging to ~/.pijota/logs
                                          --help, -h                Show this help
                                          --version, -v             Show version
                                          --license                 Show license and third-party notices

                                        EXAMPLES:
                                          %1$s                                   Start interactive mode
                                          %1$s -m anthropic/claude-sonnet-4-20250514
                                          %1$s -p "explain this code"            Single-shot query
                                          echo "explain this code" | %1$s        Single-shot query from stdin
                                          %1$s -c                                Continue last session
                                          %1$s -r                                Pick session interactively
                                          %1$s --list-models openai              List OpenAI models
                                        """.formatted(Cli.name()));
    }

    /**
     * Walks the raw argv left to right. {@link #value} consumes a required value for the flag just read; the
     * {@code optional*} variants consume the next token only when it looks like a value rather than the next flag,
     * which is the whole point of the cursor: callers never touch the index, so a flag can't accidentally swallow the
     * next flag or skip its own value.
     */
    private static final class Cursor {
        private final String[] args;
        private int i = -1;

        Cursor(String[] args) {
            this.args = args;
        }

        boolean hasNext() {
            return i + 1 < args.length;
        }

        String next() {
            return args[++i];
        }

        /** The required value for the flag just returned by {@link #next}; throws if argv ended. */
        String value(String flag) {
            if (i + 1 >= args.length)
                throw new IllegalArgumentException("Missing value for " + flag);
            return args[++i];
        }

        /**
         * An optional value, consumed only if the next token isn't itself a flag (a leading {@code -}) and isn't a
         * frontend subcommand; else null.
         *
         * <p>
         * The subcommand guard is not a nicety. {@code pijota --suggest web 127.0.0.1:8080} used to hand "web" to
         * {@code --suggest} as its guidance text, leaving no subcommand at all: the address fell through to the prompt
         * and pijota quietly ran one headless turn asking the model about {@code 127.0.0.1:8080} instead of serving
         * anything on it. Nothing in that is expressible on purpose -- the documented way to pass a value is
         * {@code --flag=value} -- so the token that names a frontend always keeps its meaning.
         */
        String optionalValue() {
            if (i + 1 >= args.length || args[i + 1].startsWith("-") || FRONTEND_SUBCOMMANDS.contains(args[i + 1]))
                return null;
            return args[++i];
        }

        /** Like {@link #optionalValue} but for a prompt: rejects {@code @file} args, allows a literal {@code ---…}. */
        String optionalPromptValue() {
            if (i + 1 >= args.length)
                return null;
            String next = args[i + 1];
            if (next.startsWith("@") || (next.startsWith("-") && !next.startsWith("---")))
                return null;
            return args[++i];
        }
    }

    private static OutputMode parseMode(String mode) {
        return switch (mode.toLowerCase()) {
            case "text" -> OutputMode.TEXT;
            case "json" -> OutputMode.JSON;
            case "rpc" -> OutputMode.RPC;
            default -> {
                System.err.println("pijota: unknown --mode '" + mode + "' (expected: text, json, rpc), using text");
                yield OutputMode.TEXT;
            }
        };
    }

    /** Warns on an unrecognized --thinking value (still defaulted downstream) so typos don't pass silently. */
    private static String checkThinking(String level) {
        if (!ThinkingLevel.isKnown(level))
            System.err.println("pijota: unknown --thinking '" + level + "' (expected: "
                                            + String.join(", ", ThinkingLevel.names()) + "), using medium");
        return level;
    }

    /** Parses {@code --max-turns} (non-negative; 0 = unlimited). Invalid → null, so the configured default applies. */
    private static Integer parseMaxTurns(String v) {
        try {
            return Math.max(0, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            System.err.println("pijota: ignoring invalid --max-turns '" + v + "'");
            return null;
        }
    }

    private static List<String> parseList(String csv) {
        var list = new ArrayList<String>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty())
                list.add(trimmed);
        }
        return list;
    }
}
