<div align="center">

# Pijota

### A coding agent that's **one native binary**, talks to **any LLM**, and can **rebuild itself**.

[![Java][badge-java]](https://openjdk.org/projects/jdk/25/)
[![GraalVM Native Image][badge-native-image]](https://www.graalvm.org/latest/reference-manual/native-image/)
[![License][badge-license]](LICENSE)

*Your code. Your rules. Your model.* No SaaS lock-in, no Python bridge, no Node.js sprawl, no JRE.

</div>

---

## What is Pijota?

Pijota is heavily derived from [Pi](https://pi.dev), rebuilt on the JVM from first principles. It shares Pi's agent architecture and extension model while adding a native GraalVM binary, an append-only terminal UI, session branching and more.

---

## Why Pijota?

- **Self-modifying.** Pijota reads, edits, rebuilds, tests, and respawns its own source — `/transcend "add a /help command"` and it ships the change with jar backup and automatic rollback if the new build breaks.
- **One binary.** GraalVM compiles it to a single native binary — optionally fully static (musl), so it runs on any Linux box with zero runtime.
- **Any model.** 20+ providers (Anthropic, OpenAI, Gemini, DeepSeek, xAI, Groq, Mistral, OpenRouter, Cerebras, Fireworks, Kimi, Moonshot, local…) — switch mid-conversation with `/model`.
- **Plays nice with your terminal.** The append-only TUI streams output into your native scrollback — scroll, copy, search with your own terminal. No alt-screen takeover; only the prompt and status line redraw.
- **Boringly portable.** Pure JVM (Java 25), zero native dependencies. Runs as a `.jar` on any JDK, or as the native binary.

---

## Quick start

```bash
# Build a native binary (or use make run for JVM)
make native

# Authenticate a provider
pijota login anthropic

# Go
pijota                            # interactive session
pijota "explain this repo"        # one-shot, then exit
```

---

## Using it

```bash
pijota                                   # interactive session in the current repo
pijota -c                                # continue the last session
pijota -r                                # interactive session picker
pijota --fork <id>                       # fork an existing session
pijota "add null-checks to UserDao"      # one-shot prompt
pijota --model openai/gpt-4o             # pick a provider/model
pijota --thinking high                   # deep reasoning
cat error.log | pijota "what broke?"     # pipe mode — stdin → agent
pijota --agent "fix all the warnings"    # headless: autonomous, for CI/hooks
pijota --web                             # serve the web UI (loopback)
pijota --telegram                        # Telegram bot frontend
pijota --list-models deepseek            # discover models
pijota --print "refactor this file"      # non-interactive, prints to stdout
pijota --export <id>                     # export session to Markdown
```

Inside a session, `/help` lists everything. Highlights: `/model` switch model · `/usage` tokens & cost · `/tree` branch the conversation · `/compact` shrink context · `/transcend` self-modify · `@file` attach a file · `!cmd` run a shell command · `/remote` web/Telegram remote control.

---

## Features

### Built-in tools

Pijota ships with a comprehensive tool set that the agent uses to read, edit, search, and execute:

| Tool | Description |
|---|---|
| `read` | Read file contents with offset/limit, capped at 50 KB |
| `write` | Write or overwrite a file, auto-creating parent directories |
| `edit` | Targeted string replacement with fuzzy-match fallback and diff output |
| `apply_patch` | Apply unified diffs across multiple files, all-or-nothing |
| `ls` | List directory contents |
| `glob` | Find files by glob pattern (uses `fd` when available, built-in Java fallback) |
| `grep` | Search file contents by regex or literal (uses `rg` when available) |
| `bash` | Execute shell commands with configurable timeout |
| `webfetch` | Fetch URLs, convert HTML to Markdown (SSRF-safe, 5 MB limit) |
| `websearch` | Web search via DuckDuckGo / Exa / Parallel.ai backends |
| `task` | Launch sub-agents with process isolation and model delegation |
| `question` | Ask the user structured questions (single, multi, or choice-based) |
| `todowrite` | Maintain a structured todo list with status and priority |
| `whoami` | Report current model, provider, and context window |

See [`/help`](src/main/java/ai/pijota/tui/HelpOverlay.java) in-session for the full command reference.

### Providers & models

| | |
|---|---|
| **20+ built-in** | Anthropic, OpenAI, Gemini, DeepSeek, xAI, Mistral, Groq, OpenRouter, Cerebras, Fireworks, Kimi, Moonshot, and more. |
| **Any compatible** | OpenAI-compatible endpoints (llama.cpp, vLLM, Qwen, Ollama) work out of the box. |
| **Per-provider config** | Custom base URLs, headers, environment variables, OAuth, thinking formats. |
| **Aliases** | `alias/fast`, `alias/current` — pin models to nicknames, switch by role. |
| **Dynamic refresh** | `/refresh` pulls the latest model list from each provider. |
| **Thinking levels** | `--thinking off\|low\|medium\|high` — controls reasoning token budgets per model. |
| **Model picker** | `/model` with fuzzy search, Levenshtein typo correction, Ctrl+P palette. |

### Append-only TUI

The terminal UI streams everything into native scrollback — no alt-screen takeover:

- **Streaming Markdown** — headers, code fences with syntax highlighting, inline styles, tables
- **Thinking block** — dimmed, collapsible reasoning area (toggle via settings)
- **Diff preview** — syntax-highlighted diffs for `edit` and `write` results
- **Command palette** — Ctrl+P: quick access to commands, models, themes, sessions
- **Log viewer** — modal scrollable/searchable viewer with live-tail (`/logs`)
- **Session browser** — arrow keys to scroll through message history
- **Conversation tree** — `/tree` with branch connectors, fork from any message
- **External editor** — `$EDITOR` integration for long prompts
- **Job control** — Ctrl+Z suspend, double-press Ctrl+C to exit
- **Multiple themes** — built-in light/dark + Catppuccin/Gruvbox variants + custom theme files

### Sessions

Sessions are durable JSONL files with branching tree structure:

- **Resume** — `/resume` interactive picker, `--continue` auto-last, `--fork <id>`
- **Branch** — `/fork` creates a new branch from any user message, `/clone` duplicates the active branch
- **Undo** — `/undo` reverts the last turn, restoring the prompt for re-edit
- **Compact** — auto-compaction when nearing the context window: the LLM summarizes old messages into a running summary
- **Export** — `/export` writes the full session to a Markdown file
- **Name** — `/name` set a descriptive label for the session picker
- **Save/load** — `/save` to JSONL, `/load` to restore

### Self-modifying (Transcendence)

`/transcend "add a /foo command"` edits, rebuilds, and respawns pijota itself:

- **Build** — runs `mvn package` in a git worktree (isolated branch)
- **Test** — `mvn test` before respawn
- **Respawn** — backs up the current jar, builds the new one, restarts into it
- **Rollback** — `/transcend --rollback` restores the previous build
- **Goal-driven** — creates a worktree + branch, instructs the agent to implement a goal, auto-merges on success
- **Safe** — auto-rollback on boot failure, Ctrl+Alt+G shortcut
- JVM-only (disabled on native image)

### Sub-agents

The `task` tool launches isolated sub-agent processes for parallel or delegated work:

- **Process isolation** — each sub-agent runs as `pijota --agent --no-session` in a separate JVM
- **Model delegation** — sub-agents can use a different model via `pijota.subagent.model`
- **Depth-limited** — max 2 levels deep to prevent runaway recursion
- **Live tracking** — per-agent status lines with tool count, output lines, tokens, timing
- **Log viewer** — `/logs` opens a live-tail viewer for sub-agent output
- **Multi-agent** — aggregate status bar showing all running agents
- **Allowlist** — sub-agents see only a subset of tools configured per agent

### Browser

The `WebBrowseExtension` (`--browse`) lets the agent navigate, extract, and interact with web pages:

- **CDP** (Chrome/Chromium) — DevTools Protocol: navigate, evaluate JS, dismiss consent walls
- **WebDriver BiDi** (Firefox + geckodriver, Safari 18+ + safaridriver) — cross-browser protocol
- **Readability** — extracts visible content with Mozilla Readability into clean Markdown
- **Lazy loading** — scroll + stability polling for SPAs
- **Idle timeout** — closes the browser after inactivity, re-launches lazily
- **SSRF-safe** — blocks internal/loopback addresses in both `webfetch` and the browser
- Pick backend with `--browse=chrome|firefox|safari`

### MCP (Model Context Protocol)

Pull in tools from any MCP server — file systems, databases, APIs, custom services:

- **Config** — `~/.pijota/mcp.json` + project-local overrides
- **Discovery** — auto-discovers tools on startup, parallel connection with bounded waits
- **Management** — `/mcp list\|connect\|disconnect\|reconnect\|reload`
- **Namespace** — `mcp__<server>__<tool>` naming
- **Stdio transport** — subprocess-based, zero network overhead

### Extensions & plugins

Pijota's extension model is the engine behind most features:

- **Event hooks** — intercept tool calls, user input, assistant output, bash execution, agent turn lifecycle
- **Slash commands** — register custom commands with aliases
- **Keybindings** — Ctrl+key chords, `$EDITOR`, custom keymaps
- **Status lines** — contribute dynamic status and footer lines
- **System prompt** — inject guidance, tool schemas, context files
- **Tool override** — block, modify, or redirect tool execution at runtime
- **Plugin discovery** — ServiceLoader (`META-INF/services`) plus directory scanning, auto-hot-reload
- **Headless boot** — `--agent` mode loads extensions without TUI, for sub-agents and CI

Built-in example extensions: inline bash (`!cmd`), context files (`AGENTS.md`), goal tracking, scheduled reminders, token speed metrics, git auto-checkpoint, permission gating, output filtering, structured JSON output, and more.

### Skills

Skills are reusable Markdown files containing instructions and workflows:

- Stored in `~/.pijota/skills/` or explicit paths (`--skill`)
- Listed in the system prompt for model awareness
- `skill` tool: list available skills, read bodies, invoke by name
- `/skill:<name>` slash command for direct invocation
- First-class in the skill picker (Ctrl+P `skill:` prefix)

### Web UI & Telegram

The same agent session can be accessed through multiple frontends:

- **Web UI** (`--web`) — SSE-powered single-page app with real-time streaming, dialogs, and pending-message replay for new tabs
- **Telegram** (`--telegram`) — full agent over chat with inline keyboards, image/document input, per-chat isolation
- **Remote control** — `/remote` starts the web server or Telegram bridge from the TUI, with QR codes for mobile access

### Headless & pipe modes

- **`--print`** — non-interactive single-shot, writes to stdout; uses the `print` model alias for cheaper runs
- **`--agent`** — autonomous JSON-RPC mode for CI hooks, sub-agents, programmatic orchestration
- **Pipe** — `stdin` is attached as the first user message when present
- **`@file`** — attach files as message content from the command line

---

## Optional external tools

Pijota is a single self-contained binary, but a few features shell out to external CLIs when present. It probes
`PATH` at runtime (cross-platform, `PATHEXT`-aware on Windows) and **degrades gracefully** — install only what you need.

| Tool | Unlocks | If missing |
|---|---|---|
| **ripgrep** (`rg`) | the `grep` tool — fast file-content search | `grep` returns an install hint instead of results |
| **fd** (`fd`) | fast `glob` file discovery | falls back to a built-in Java directory walker (slower) |
| **fzf** | the `@`-file fuzzy picker in the TUI | falls back to inline path completion |
| **Chrome / Chromium** | the browser tool over CDP — navigate, click, screenshot, extract page/PDF text | use Firefox/Safari instead, or the browser tool stays off |
| **Firefox** + **geckodriver** | the browser tool over WebDriver BiDi (alternate engine) | use Chrome/Safari instead |
| **Safari** + `safaridriver` | the browser tool on macOS over BiDi (Safari 18+) | macOS only |
| **tmux** | the `--tmux` extension — run and observe commands in detached terminal panes | the extension reports tmux is unavailable |
| **git** | `/transcend` self-rebuild, sub-agent worktrees, session checkpoints | those features are unavailable |

Browser backends are interchangeable — any one of Chrome/Chromium, Firefox (+geckodriver), or Safari (+safaridriver)
is enough. Pick the engine with `--browse=chrome|firefox|safari` (default `chrome`); point at a specific binary with
`PIJOTA_BROWSER_BIN` / `-Dpijota.browse.bin` if it isn't auto-discovered on `PATH`.

---

## Build from source

Requires JDK 25 (a GraalVM JDK for native builds). `make` wraps `mvn` with sensible defaults.

```bash
make run            # compile + launch  (ARGS="--help" to pass flags)
make build          # compile only
make test           # unit tests
make check          # format-check + tests
make jar            # thin jar
make fat            # uber-jar  →  java -jar target/pijota-*-fat.jar
make native         # GraalVM native image
make native-static  # fully static musl binary  (needs: make setup-musl)
make coverage       # JaCoCo report
make format         # apply Spotless formatting
make clean
```

For a PGO-optimized native build: `make native-pgo-instrument`, run a representative session, then `make native-pgo-build`.

---

## License

[Apache 2.0](LICENSE).

[badge-java]: https://img.shields.io/badge/Java-25+-blue
[badge-native-image]: https://img.shields.io/badge/GraalVM-Native_Image-F29111?labelColor=00758F
[badge-license]: https://img.shields.io/badge/license-Apache%202.0-green
