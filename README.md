<div align="center">

# Pijota

### A coding agent that's **one native binary**, talks to **any LLM**, and can **rebuild itself**.

[![Java][badge-java]](https://openjdk.org/projects/jdk/25/)
[![GraalVM Native Image][badge-native-image]](https://www.graalvm.org/latest/reference-manual/native-image/)
[![License][badge-license]](LICENSE)

*Your code. Your rules. Your model.* No SaaS lock-in, no Python bridge, no Node.js sprawl, no JRE.

</div>

---

---

## What is Pijota?

Pijota is heavily derived from [Pi](https://pi.dev), rebuilt on the JVM from first principles. It shares Pi's agent architecture and extension model while adding a native GraalVM binary, an append-only terminal UI, session branching and more.

---

## Why Pijota?

- **🜂 Self-modifying.** Pijota reads, edits, rebuilds, tests, and respawns its own source — `/transcend "add a /help command"` and it ships the change with jar backup and automatic rollback if the new build breaks.
- **📦 One binary.** GraalVM compiles it to a single native binary — optionally fully static (musl), so it runs on any Linux box with zero runtime.
- **🔌 Any model.** 20+ providers (Anthropic, OpenAI, Gemini, DeepSeek, xAI, Groq, Mistral, OpenRouter, Cerebras, Fireworks, Kimi, Moonshot, local…) — switch mid-conversation with `/model`.
- **🖥️ Plays nice with your terminal.** The append-only TUI streams output into your native scrollback — scroll, copy, search with your own terminal. No alt-screen takeover; only the prompt and status line redraw. Same agent also available as a web UI (`--web`) or Telegram bot (`--telegram`).
- **☕ Boringly portable.** Pure JVM (Java 25), zero native dependencies. Runs as a `.jar` on any JDK, or as the native binary.

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
pijota "add null-checks to UserDao"      # one-shot prompt
pijota --model openai/gpt-4o             # pick a provider/model
cat error.log | pijota "what broke?"     # pipe mode — stdin → agent
pijota --agent "fix all the warnings"    # headless: autonomous, for CI/hooks
pijota --web                             # serve the web UI (loopback)
pijota --list-models deepseek            # discover models
```

Inside a session, `/help` lists everything. Highlights: `/model` switch model · `/usage` tokens & cost · `/tree` branch the conversation · `/compact` shrink context · `/transcend` self-modify · `@file` attach a file · `!cmd` run a shell command.

---

## Features

| | |
|---|---|
| **20+ Providers** | Anthropic, OpenAI, Gemini, DeepSeek, xAI, Mistral, Groq, OpenRouter, Cerebras, Fireworks, Kimi, Moonshot, local — hot-switch mid-session. |
| **Native Binary** | GraalVM → one binary. Optionally fully static (musl). No JRE, no Docker. |
| **Self-modifying** | `/transcend "add /foo"` — edits, rebuilds, tests, respawns itself. Backup + auto-rollback. |
| **Append-only TUI** | Streams into native scrollback. Streaming markdown, syntax highlighting, diffs, session browser, command palette, 6 themes. |
| **Web UI** | `--web` — SSE-powered single-page browser interface. |
| **Telegram Bot** | `--telegram` — full agent over chat. |
| **Pipe & Headless** | `... \| pijota "?"` for shells/CI; `--agent` for autonomous JSON-I/O. |
| **Browser** | CDP + WebDriver BiDi — browse, click, extract. |
| **MCP** | Model Context Protocol — pull in tools from any MCP server. |
| **Extensions** | ServiceLoader plugins: custom tools, slash commands, keybindings, status bars, event hooks. |
| **Sessions** | Durable, local: resume, fork/branch, compact, export. |

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
