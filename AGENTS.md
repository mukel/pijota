# Pijota: project reference for coding agents

Pijota is a self-modifying coding agent for the terminal: a single native binary that talks to
any LLM. `CLAUDE.md` is a symlink to this file, so there is one set of conventions, not two.

## Quick start

```bash
make run ARGS="--help"     # JVM mode
make native                # native binary, writes target/pj
make test                  # run tests
make check                 # format-check + tests
make format                # apply Spotless
```

## Build system

Maven with Java 21 (`maven.compiler.release`). Spotless with the Eclipse 4.34 formatter
(`eclipse-formatter.xml`); `make format` applies it, `make check` verifies. Favour the standard
library over new dependencies. Comments are documentation, not noise.

## Architecture

- **Entry**: `org.pijota.Main`. Parses args, bootstraps, dispatches to TUI/headless/web/telegram
- **Bootstrap**: `AppBootstrap`. Wires settings, LLM, extensions, session, tools
- **Agent**: `agent/AgentLoop`. The conversation loop; `agent/ToolExecutor` dispatches tool calls
- **TUI**: `tui/AppendOnlyTui`. Append-only terminal UI with streaming markdown
- **Extensions**: `Extension` SPI. Hook into tool calls, add slash commands, keybindings, status lines
- **Sub-agents**: `agent/SubAgentLauncher` spawns separate JVM processes for parallel work
- **Self-modify**: `extensions/transcend/TranscendenceOrchestrator`. Worktree edit/rebuild/respawn

## Project structure

```
src/main/java/org/pijota/   main source
  acp/                       Agent Client Protocol frontend
  agent/                     agent loop, sessions, sub-agents
  browser/                   CDP/BiDi browser automation
  cli/                       arg parsing
  config/                    settings, OAuth, provider catalog, project trust
  extensions/                extension SPI + built-in extensions
  llm/                       LLM model registry, streaming
  messages/                  message/tool-call/usage records
  modes/                     print mode, RPC mode
  telegram/                  Telegram bot frontend
  theme/                     ANSI theme model
  tools/                     built-in tools (bash, edit, grep, apply-patch, …)
  tui/                       append-only terminal UI
  util/                      utilities
  web/                       web UI server
  worktree/                  pooled git worktrees
  Main.java                  entry point
  AppBootstrap.java          dependency wiring
  ThemeLoader.java           ANSI theme loading
src/main/resources/          models.json, providers.json, logback.xml,
                             themes/, agents/, skills/, web/, browser/, META-INF/
src/test/                    tests mirroring main structure
```

## Key files

- `pom.xml`: Maven build with native-image, fat jar, and static profiles
- `Makefile`: dev-loop convenience (build, test, run, native, format)
- `src/main/resources/models.json`: model registry (53 models)
- `src/main/resources/providers.json`: 22 provider configurations
- `eclipse-formatter.xml`: code style (Spotless)

## Key conventions

- Extensions use ServiceLoader (`META-INF/services/org.pijota.extensions.Extension`)
- System prompt and tool schemas are built from extensions' contributions
- The TUI is append-only (no alt-screen), uses JLine for prompt editing
- Session files are JSONL in `~/.pijota/sessions/`
- Settings in `~/.pijota/settings.json`
- Anything bundled and loaded by name (skills, agents, themes) also needs its entry in the
  matching `BUILTIN_*` list, or it does not exist in the native image
