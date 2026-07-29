# AGENTS.md — Pijota coding agent conventions

## Build system

Maven with Java 21. `make` wraps common tasks:
- `make build` — compile
- `make test` — run tests
- `make run` — compile + launch (pass ARGS="...")
- `make check` — format-check + tests

## Code style

- Java 21, Spotless with Eclipse 4.34 formatter (`eclipse-formatter.xml`)
- `make format` applies formatting; `make check` verifies in CI
- Favor standard library over new dependencies
- Comments are documentation, not noise

## Project structure

```
src/main/java/org/pijota/  — main source
  acp/                      — Agent Client Protocol frontend
  agent/                    — agent loop, sessions
  browser/                  — CDP/BiDi browser automation
  cli/                      — arg parsing
  config/                   — settings, OAuth, provider catalog
  extensions/               — extension SPI + built-in extensions
  llm/                      — LLM model registry, streaming
  messages/                 — message/tool-call/usage records
  modes/                    — print mode, RPC mode
  telegram/                 — Telegram bot frontend
  theme/                    — ANSI theme model
  tools/                    — built-in tools (bash, edit, grep, apply-patch, …)
  tui/                      — append-only terminal UI
  util/                     — utilities
  web/                      — web UI server
  worktree/                 — self-modify worktree management
  Main.java                 — entry point
  AppBootstrap.java         — dependency wiring
  ThemeLoader.java          — ANSI theme loading
src/main/resources/         — models.json, providers.json, themes/, agents/, web/, browser/
src/test/                   — tests mirroring main structure
```

## Key conventions

- Extensions use ServiceLoader (`META-INF/services/org.pijota.extensions.Extension`)
- System prompt and tool schemas are built from extensions' contributions
- The TUI is append-only (no alt-screen), uses JLine for prompt editing
- Session files are JSONL in `~/.pijota/sessions/`
- Settings in `~/.pijota/settings.json`
