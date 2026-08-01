# CLAUDE.md: Pijota project reference

Pijota is a self-modifying coding agent for the terminal, a single native binary that talks to any LLM.

## Quick start

```bash
make run ARGS="--help"     # JVM mode
make native                # native binary
make test                  # run tests
```

## Architecture

- **Entry**: `org.pijota.Main`. Parses args, bootstraps, dispatches to TUI/headless/web/telegram
- **Bootstrap**: `AppBootstrap`. Wires settings, LLM, extensions, session, tools
- **Agent**: `AgentLoop`. The main conversation loop; `ToolExecutor` dispatches tool calls
- **TUI**: `AppendOnlyTui`. Append-only terminal UI with streaming markdown
- **Extensions**: `Extension` SPI. Hook into tool calls, add slash commands, keybindings, status lines
- **Sub-agents**: `SubAgentLauncher` spawns separate JVM processes for parallel work
- **Self-modify**: `TranscendenceOrchestrator`. Worktree-based edit/rebuild/respawn cycle

## Key files

- `pom.xml`: Maven build with native-image, fat jar, and static profiles
- `Makefile`: dev-loop convenience (build, test, run, native, format)
- `src/main/resources/models.json`: model registry (~400 models)
- `src/main/resources/providers.json`: 20+ provider configurations
- `eclipse-formatter.xml`: code style (Spotless)
