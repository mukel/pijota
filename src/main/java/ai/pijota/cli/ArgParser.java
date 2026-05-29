package ai.pijota.cli;

import ai.pijota.cli.Args.OutputMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ArgParser {

    public static Args parse(String[] rawArgs) {
        var provider = (String) null;
        var model = (String) null;
        var apiKey = (String) null;
        var systemPrompt = (String) null;
        var appendSystemPrompts = new ArrayList<String>();
        var thinkingLevel = (String) null;
        var outputMode = OutputMode.TEXT;
        var sessionPath = (String) null;
        var noSession = false;
        var noTools = false;
        var noExtensions = false;
        var noSkills = false;
        var noThemes = false;
        var noContextFiles = false;
        var enabledTools = new ArrayList<String>();
        var extensionPaths = new ArrayList<String>();
        var skillPaths = new ArrayList<String>();
        var themePaths = new ArrayList<String>();
        var print = false;
        var resume = false;
        var continueSession = false;
        var recoverSession = false;
        var forkPath = (String) null;
        var exportPath = (String) null;
        var listModels = false;
        var listModelsFilter = (String) null;
        var help = false;
        var version = false;
        var verbose = false;
        var agent = false;
        var worker = false;
        var fileArgs = new ArrayList<String>();
        var promptArgs = new ArrayList<String>();
        var extensionFlags = new LinkedHashMap<String, String>();

        for (int i = 0; i < rawArgs.length; i++) {
            String arg = rawArgs[i];
            switch (arg) {
                case "--provider" -> provider = nextArg(rawArgs, i++);
                case "--model", "-m" -> {
                    String value = nextArg(rawArgs, i++);
                    int slash = value.indexOf('/');
                    if (slash > 0 && slash < value.length() - 1) {
                        provider = value.substring(0, slash);
                        model = value.substring(slash + 1);
                    } else {
                        model = value;
                    }
                }
                case "--api-key" -> apiKey = nextArg(rawArgs, i++);
                case "--system-prompt" -> systemPrompt = nextArg(rawArgs, i++);
                case "--append-system-prompt" -> appendSystemPrompts.add(nextArg(rawArgs, i++));
                case "--thinking" -> thinkingLevel = nextArg(rawArgs, i++);
                case "--mode" -> outputMode = parseMode(nextArg(rawArgs, i++));
                case "--session", "-s", "--session-path" -> sessionPath = nextArg(rawArgs, i++);
                case "--fork" -> forkPath = nextArg(rawArgs, i++);
                case "--export" -> exportPath = nextArg(rawArgs, i++);
                case "--tools", "-t" -> enabledTools.addAll(parseList(nextArg(rawArgs, i++)));
                case "--extension", "-e" -> extensionPaths.add(nextArg(rawArgs, i++));
                case "--skill" -> skillPaths.add(nextArg(rawArgs, i++));
                case "--theme" -> themePaths.add(nextArg(rawArgs, i++));
                case "--list-models" -> {
                    listModels = true;
                    if (i + 1 < rawArgs.length && !rawArgs[i + 1].startsWith("-")) {
                        listModelsFilter = rawArgs[++i];
                    }
                }
                case "--print", "-p" -> {
                    print = true;
                    if (i + 1 < rawArgs.length) {
                        String next = rawArgs[i + 1];
                        if (!next.startsWith("@") && (!next.startsWith("-") || next.startsWith("---"))) {
                            promptArgs.add(next);
                            i++;
                        }
                    }
                }
                case "--resume", "-r" -> resume = true;
                case "--continue", "-c" -> continueSession = true;
                case "--recover" -> recoverSession = true;
                case "--no-session" -> noSession = true;
                case "--no-tools", "-nt" -> noTools = true;
                case "--no-extensions", "-ne" -> noExtensions = true;
                case "--no-skills", "-ns" -> noSkills = true;
                case "--no-themes" -> noThemes = true;
                case "--no-context-files", "-nc" -> noContextFiles = true;
                case "--verbose" -> verbose = true;
                case "--help", "-h" -> help = true;
                case "--version", "-v" -> version = true;
                case "--agent" -> agent = true;
                case "--worker" -> worker = true;
                default -> {
                    if (arg.startsWith("--") && arg.length() > 2) {
                        String name = arg.substring(2);
                        int eq = name.indexOf('=');
                        if (eq > 0) {
                            extensionFlags.put(name.substring(0, eq), name.substring(eq + 1));
                        } else if (i + 1 < rawArgs.length && !rawArgs[i + 1].startsWith("-")) {
                            extensionFlags.put(name, rawArgs[++i]);
                        } else {
                            extensionFlags.put(name, "true");
                        }
                    } else if (arg.startsWith("-")) {
                    } else if (arg.startsWith("@")) {
                        fileArgs.add(arg.substring(1));
                    } else if (!arg.startsWith("-")) {
                        promptArgs.add(arg);
                    }
                }
            }
        }

        return new Args(provider, model, apiKey, systemPrompt, appendSystemPrompts,
            thinkingLevel, outputMode, sessionPath, noSession, noTools, noExtensions, noSkills, noThemes,
            noContextFiles, enabledTools, extensionPaths, skillPaths, themePaths, print, resume, continueSession,
            recoverSession, forkPath, exportPath, listModels, listModelsFilter, help, version, verbose, agent, worker,
            fileArgs, promptArgs, Map.copyOf(extensionFlags));
    }

    public static void printHelp() {
        System.out.println("""
            pijota - minimalist Java coding agent

            USAGE: pijota [OPTIONS] [PROMPT...]

            OPTIONS:
              --provider <name>         LLM provider (openai, anthropic, google, ...)
              --model, -m <id>          Model name or provider/id shorthand
              --api-key <key>           API key
              --system-prompt <text>    Custom system prompt
              --append-system-prompt <text>  Append to system prompt (repeatable)
              --thinking <level>        Thinking level (off, low, medium, high)
              --mode <mode>             Output mode: text, json, rpc
              --print, -p [prompt]      Non-interactive single-shot mode
              --session, -s <id>        Session file path or partial UUID
              --continue, -c            Continue last session
              --recover               Recover from failed transcendence
              --resume, -r              Interactive session picker
              --fork <id>               Fork a session
              --no-session              Don't save session
              --tools, -t <names>       Comma-separated tool allowlist
              --no-tools, -nt           Disable all tools
              --no-extensions, -ne      Skip extension auto-discovery
              --no-skills, -ns          Skip skill auto-discovery
              --extension, -e <path>    Load extension
              --skill <path>            Load skill
              --theme <path|name>       Load/select theme (repeatable)
              --no-themes               Skip theme discovery
              --no-context-files, -nc    Skip AGENTS.md and CLAUDE.md discovery
              --export <file>           Export session to Markdown
              --list-models [filter]    List available models
              --verbose                 Verbose output
              --help, -h                Show this help
              --version, -v             Show version

            EXAMPLES:
              pijota                                   Start interactive mode
              pijota -m anthropic/claude-sonnet-4-20250514
              pijota -p "explain this code"            Single-shot query
              echo "explain this code" | pijota        Single-shot query from stdin
              pijota -c                                Continue last session
              pijota -r                                Pick session interactively
              pijota --list-models openai              List OpenAI models
            """);
    }

    private static String nextArg(String[] args, int i) {
        if (i + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for " + args[i]);
        }
        return args[i + 1];
    }

    private static OutputMode parseMode(String mode) {
        return switch (mode.toLowerCase()) {
            case "json" -> OutputMode.JSON;
            case "rpc" -> OutputMode.RPC;
            default -> OutputMode.TEXT;
        };
    }

    private static List<String> parseList(String csv) {
        var list = new ArrayList<String>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) list.add(trimmed);
        }
        return list;
    }
}
