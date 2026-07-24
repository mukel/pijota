<div align="center">

# Pijota

### A local-first AI coding agent for people who live in terminals.

[![Java 25][badge-java]][java]
[![Maven][badge-maven]][maven]
[![GraalVM Native Image][badge-native]][graalvm]
[![License: Apache 2.0][badge-license]][license]
[![Local First][badge-local]][repo]
[![Providers][badge-providers]][providers]

**One JVM app. One optional native binary. Any model. Your code. Your rules.**

The command is short: **`pj`**.

</div>

---

## What Is Pijota?

Pijota is an AI coding agent written in Java. It reads code, edits files, runs commands, browses the web, talks to MCP servers, manages durable sessions, and is developed using Pijota itself.

It is heavily inspired by [Pi](https://pi.dev), **unashamedly so**. Pijota borrows Pi's spirit: small sharp tools, agentic loops, extension-driven behavior, and the belief that a coding agent should be hackable by the people using it. This project reimagines that shape on the JVM with a GraalVM-native path, an append-only TUI, JSONL sessions, browser automation, ACP (Agent Client Protocol), web/Telegram frontends, and a pragmatic Java implementation.

If Pi is the Vim-shaped agent, Pijota is not trying to be a smaller Pi. `pj` keeps the TUI simple: it appends to normal terminal scrollback. The rest stays powerful, extensible, and customizable.

> [!NOTE]
> Pijota is local software with powerful tools. When you give it shell or file access, it acts with your user permissions.

---

## Why Pijota?

| | |
|---|---|
| **Native terminal feel** | Append-only output streams into real scrollback. No alt-screen takeover. Copy, search, scroll, and multiplex however your terminal already works. |
| **Bring your own model** | OpenAI, Anthropic, Gemini, OpenAI-compatible APIs, local endpoints, custom providers, aliases, OAuth, API keys, and thinking levels. |
| **Real coding tools** | Read, write, edit, patch, grep, glob, run bash, fetch URLs, ask questions, delegate to sub-agents, and pull in MCP tools. |
| **Durable memory** | Sessions are append-only JSONL files with continue, resume, fork, clone, compaction, naming, and Markdown export. |
| **Parallel experiments** | `pj worktree` manages a reusable git worktree pool for isolated shells, scripts, and cleanup. |
| **Multiple frontends** | Use the TUI, print mode, JSON output, JSON-RPC, ACP for editors, the local web UI, or a Telegram bot. |
| **Hackable core** | Extensions can add tools, commands, keybindings, status lines, prompt context, input hooks, and safety policies. |
| **Self-modifying** | In JVM mode, the opt-in transcendence flow can edit Pijota, build it, test it, respawn, and roll back if something goes wrong. |

---

## Quick Start

Requires **JDK 25**. Native builds require a GraalVM JDK. From source, `make run` launches Pijota directly; install or alias the built artifact as `pj` for daily use.

```bash
make run ARGS="--help"
make run ARGS="login anthropic"
make run ARGS="explain this repo"
```

For a regular local build:

```bash
make build
make test
make run
```

For a native binary:

```bash
make native
./target/pijota
mkdir -p ~/.local/bin
ln -sf "$PWD/target/pijota" ~/.local/bin/pj
```

---

## Everyday Usage

```bash
pj                                        # interactive TUI
pj tui                                    # explicit TUI
pj "explain this repo"                    # one-shot prompt
pj -p "summarize the diff"                # print mode
cat error.log | pj "what broke?"          # stdin becomes the prompt

pj -m anthropic/claude-sonnet-4-5-20250929
pj --thinking high
pj --max-turns 64

pj -c                                     # continue last session
pj -r                                     # session picker
pj -s <session-id-or-path>                 # load by id/path
pj --fork <entry-id>                       # fork at an entry
pj --export transcript.md                  # export current/loaded session

pj web                                    # local web UI on 127.0.0.1:8420
pj web 0.0.0.0:8420                       # token-gated network bind
pj telegram                               # Telegram bot frontend
pj acp                                    # Agent Client Protocol over stdio

pj worktree                               # open a temporary pooled git worktree
pj worktree get                           # reserve a worktree path for scripts
pj worktree status                        # inspect the worktree pool
```

Inside the TUI, start with `/help`.

| Command | Purpose |
|---|---|
| `/model` | Switch models with a picker or query. |
| `/settings` | Open runtime settings. |
| `/compact` | Summarize older context to stay inside the window. |
| `/usage` | Show tokens, cost, and context information. |
| `/sessions` | Resume a previous session. |
| `/tree` | View conversation branches. |
| `/fork` | Fork from a previous message. |
| `/clone` | Clone the current branch. |
| `/theme` | Switch built-in or custom themes. |
| `/remote` | Pair web or Telegram remote control. |
| `/skills` | List reusable skill instructions. |

---

## Frontends

Every frontend drives the same agent/session core. ACP is first-class: `pj acp` lets editor clients such as Zed, JetBrains, and Neovim use Pijota over stdio, with client-side permission prompts for mutating tools.

| Frontend | Command | Notes |
|---|---|---|
| **TUI** | `pj` | Append-only terminal UI with Markdown, syntax highlighting, status lines, selectors, themes, hotkeys, and file references. |
| **Print** | `pj -p "..."` | Single-shot mode for scripts and pipes. Supports text or JSON output via `--mode`. |
| **RPC** | `pj --mode rpc` | JSON-RPC over stdin/stdout with `prompt`, `ping`, and `shutdown`. |
| **ACP** | `pj acp` | Agent Client Protocol for editor integrations, with approval prompts for mutating tools. |
| **Web** | `pj web [host:port]` | Zero-framework JDK HTTP server, SSE streaming, local chat UI, and token pairing for non-loopback binds. |
| **Telegram** | `pj telegram` | Single-chat bot with streaming edits, stop button, slash commands, files, photos, and exports. |

---

## Terminal Polish

The TUI is designed to feel like a good Unix program, not a captive fullscreen app. It appends to normal terminal scrollback, so your terminal stays in charge.

| Detail | Why it matters |
|---|---|
| **Append-only rendering** | Output stays in normal terminal history, so scrollback, copy, search, panes, and recording tools keep working. |
| **Rich Markdown** | Headings, code fences, tables, inline styles, and streamed formatting render cleanly with ANSI themes. |
| **Diff-first editing** | File edits and writes get readable inline diffs. |
| **Fuzzy pickers** | Models, sessions, themes, files, skills, and commands can be found quickly. |
| **Status lines** | Cost, tokens, context, tools, sub-agents, jobs, and extension state stay visible without taking over the screen. |
| **Keyboard flow** | Command palette, hotkey help, `$EDITOR` support, undo, copy-last-response, and interrupt handling are built in. |
| **Themes** | Bundled Gruvbox and Catppuccin variants, terminal light/dark auto-detection, custom theme files, and `NO_COLOR` support. |
| **File references** | Use `@file` to inline files or attach prompt context from the command line and TUI. |

---

## Tools

Pijota exposes a compact tool belt to the model. Tool visibility can be scoped with `--tools`, `--exclude-tools`, `--no-tools`, and `--no-builtin-tools`.

| Tool | What it does |
|---|---|
| `read` | Read files with offset and limit support. |
| `write` | Write files and create parent directories. |
| `edit` | Replace exact text, including multi-edit operations. |
| `apply_patch` | Apply structured patches. |
| `glob` | Find files by glob pattern. |
| `grep` | Search file contents with regular expressions. |
| `ls` | List directory contents. |
| `bash` | Run shell commands with timeout handling. |
| `webfetch` | Fetch URLs and return readable Markdown. |
| `websearch` | Search the web through configured search backends. |
| `question` | Ask the user for a decision when the agent is genuinely blocked. |
| `task` | Launch isolated sub-agents through the task extension. |
| `todo` | Maintain structured todos when `--todo` is enabled. |
| `whoami` | Report model/provider details when `--whoami` is enabled. |
| `transcendence` | Modify and rebuild Pijota when `--transcendence` is enabled. |

---

## Optional External Tools

Pijota is self-contained, but it uses familiar command-line tools when they are available.

| Tool | Unlocks |
|---|---|
| `rg` | Fast content search for `grep`. |
| `fd` | Fast file discovery for `glob`; Java fallback exists. |
| `fzf` | Fuzzy file picking for `@` references. |
| Chrome or Chromium | Browser automation through CDP. |
| Firefox plus geckodriver | Browser automation through WebDriver BiDi. |
| Safari plus safaridriver | Browser automation on macOS through WebDriver BiDi. |
| `tmux` | Long-running supervised processes through `--tmux`. |
| `git` | Worktrees, transcendence, and source-aware workflows. |

Shell commands launched by Pijota receive the current session context:

| Variable | Value |
|---|---|
| `PIJOTA_PROVIDER` | Current model provider. |
| `PIJOTA_MODEL` | Current model ID. |
| `PIJOTA_REASONING_LEVEL` | Current reasoning level. |
| `PIJOTA_SESSION_FILE` | Current session JSONL path, when persisted. |
| `PIJOTA_SESSION_ID` | Current session ID, when persisted. |

---

## Extensions

Extensions are the real shape of Pijota. They can register tools, slash commands, keybindings, status/footer lines, system-prompt context, message renderers, safety hooks, and event listeners.

Toggle built-ins uniformly with `--<name>` or `--no-<name>`, and persist choices in `~/.pijota/settings.json`.

```jsonc
{
  "extensions": {
    "browse": "firefox",
    "jobs": true,
    "todo": true,
    "transcendence": false
  }
}
```

| Extension | Default | Purpose |
|---|---:|---|
| `browse` | off | Real browser browsing through Chrome, Firefox, or Safari. |
| `jobs` | off | Background shell jobs with output/list/kill tools. |
| `tmux` | off | Supervise long-lived processes in tmux. |
| `todo` | off | Structured todo list and live panel. |
| `token-speed` | off | Tokens-per-second status line. |
| `transcendence` | off | Self-modification, build, test, respawn, rollback. |
| `whoami` | off | Current model/provider information tool. |
| `skills` | on | Skill auto-discovery. |
| `prompt-templates` | on | Prompt-template slash commands. |
| `context-files` | on | `AGENTS.md` and `CLAUDE.md` context discovery. |

Always-on core extensions provide planning, sub-agents, MCP, inline bash commands, goal tracking, scheduled prompts, output filtering, security hooks, whimsical status text, side conversations, and shared slash commands.

---

## Models And Providers

Pijota ships with a provider catalog and model registry. It supports first-class provider adapters plus OpenAI-compatible endpoints.

| Provider family | Examples |
|---|---|
| **Native adapters** | Anthropic, Google Gemini, OpenAI-compatible APIs. |
| **Hosted providers** | OpenAI, Anthropic, Google AI, DeepSeek, Groq, xAI, Mistral, Together AI, OpenRouter, Cerebras, Fireworks AI, Kimi, Moonshot, Z.AI, Hugging Face, NVIDIA NIM, MiniMax. |
| **Special auth** | OpenAI Codex OAuth device flow. |
| **Local/custom** | llama.cpp, vLLM, local OpenAI-compatible servers, and user-defined providers in settings. |

Model quality-of-life features:

- `provider/model` shorthand, for example `openai/gpt-5.5`.
- Model aliases such as `alias/fast`, `alias/current`, and `alias/print`.
- Thinking levels: `off`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`.
- Per-model context windows, pricing, modalities, and typo suggestions.
- API keys from environment variables, settings, or OAuth token stores.

---

## Sessions

Sessions are append-only JSONL files under `~/.pijota/sessions/` by default.

| Capability | Description |
|---|---|
| **Resume** | Continue the latest session with `-c` or pick one with `-r` and `/sessions`. |
| **Branch** | Fork from earlier entries and keep a real conversation tree. |
| **Clone** | Duplicate the active branch into a new session. |
| **Compact** | Automatically summarize older context before the model window fills. |
| **Export** | Write a transcript to Markdown. |
| **Recover** | Restore interrupted self-modification sessions with `--recover`. |
| **Track usage** | Persist token counts, cache reads/writes, reasoning tokens, and cost. |

---

## Browser, MCP, And Skills

| Feature | Details |
|---|---|
| **Browser** | Enable with `--browse` or `--browse=chrome|firefox|safari`. Chrome uses CDP; Firefox and Safari use WebDriver BiDi. Pages are rendered, consent banners are dismissed, lazy content is settled, and Markdown is extracted with Mozilla Readability fallbacks. |
| **PDFs** | Browser mode can extract digital PDF text through PDF.js. |
| **MCP** | Configure servers in `~/.pijota/mcp.json`; tools appear as `mcp__<server>__<tool>`. Manage live servers with `/mcp`. |
| **Skills** | Reusable Markdown workflows from `~/.pijota/skills/`, `.pijota/skills/`, or `--skill`. Invoke with `/skill:<name>` or the `skill` tool. |
| **Prompt templates** | Drop reusable prompts into configured template paths and run them as slash commands. |
| **Context files** | `AGENTS.md` and `CLAUDE.md` are discovered automatically unless disabled. |

---

## Transcendence

`--transcendence` enables Pijota's riskiest and most distinctive trick: self-modification.

When asked to change itself, Pijota can edit its own Java source, build, test, create a replacement jar, ask the supervisor to respawn, and roll back if something goes wrong.

```bash
pj --transcendence
/transcend add a tiny /doctor command that checks provider configuration
```

This is JVM-only and intentionally opt-in.

---

## Configuration

Pijota stores settings in `~/.pijota/settings.json` with owner-only permissions for files that hold API keys.

Common settings include:

- `default_provider` and `default_model`.
- `api_keys` per provider.
- `thinking_level`.
- `session_dir` and `project_dir`.
- `theme`, `themes`, `path_display`, and `markdown`.
- `aliases` and `enabled_models`.
- `providers` for custom LLM endpoints.
- `extensions` for persisted extension state.
- `compaction` with reserve and keep-recent token counts.

Login helpers:

```bash
pj login anthropic
pj login openai-codex
pj logout anthropic
```

Environment variables such as `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`, and provider-specific keys are also supported.

---

## Build From Source

`make` wraps Maven and prefers `mvnd` when available.

| Command | Purpose |
|---|---|
| `make build` | Compile. |
| `make test` | Run unit tests. |
| `make test-live` | Run opt-in live browser tests. |
| `make check` | Format check plus tests. |
| `make run` | Compile and launch, passing args with `ARGS="..."`. |
| `make jar` | Build a thin jar. |
| `make fat` | Build an uber-jar. |
| `make native` | Build a GraalVM native image. |
| `make native-static` | Build a fully static musl native image after `make setup-musl`. |
| `make coverage` | Generate a JaCoCo report. |
| `make format` | Apply Spotless formatting. |

PGO native builds are supported:

```bash
make native-pgo-instrument
./target/pijota
make native-pgo-build
```

---

## Security Model

Pijota is not a sandbox. It can edit files and run commands in the environment where you launch it.

Practical safety features exist: tool scoping, ACP permission prompts, project-local extension trust, SSRF checks for web fetch/browser paths, opt-in browser launch confirmation, owner-only settings files, and opt-in self-modification. These reduce risk; they do not replace process isolation.

If you need hard isolation, run Pijota inside a container, VM, locked-down user account, or disposable worktree.

---

## License

[Apache 2.0][license]

[repo]: .
[license]: LICENSE
[providers]: src/main/resources/providers.json
[java]: https://openjdk.org/projects/jdk/25/
[maven]: https://maven.apache.org/
[graalvm]: https://www.graalvm.org/latest/reference-manual/native-image/
[badge-java]: https://img.shields.io/badge/Java-25+-007396?style=flat-square&logo=openjdk&logoColor=white
[badge-maven]: https://img.shields.io/badge/build-Maven-C71A36?style=flat-square&logo=apachemaven&logoColor=white
[badge-native]: https://img.shields.io/badge/GraalVM-native_image-F29111?style=flat-square&logo=graalvm&logoColor=white
[badge-license]: https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square
[badge-local]: https://img.shields.io/badge/local--first-no_SaaS_lock--in-2ea44f?style=flat-square
[badge-providers]: https://img.shields.io/badge/providers-20%2B-7c3aed?style=flat-square
