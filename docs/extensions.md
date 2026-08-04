# Extensions

Add tools, slash commands, keybindings, status lines, prompt context and safety hooks.

## Toggling

```bash
pj --todo              # on, this run
pj --no-jobs           # off, this run
```

```json
{ "extensions": { "todo": true, "jobs": false } }
```

A value works the same on the command line and in settings:

```bash
pj --browse=firefox
pj --suggest="Suggest the next step, never a question."
pj --suggest=~/prompts/suggest.md      # a readable path is read as the prompt
```

| Flag | Effect |
|---|---|
| `--no-extensions`, `-ne` | nothing loads |
| `--no-builtin-extensions`, `-nbe` | only discovered extensions load |

## Shipped, on

| Name | Does |
|---|---|
| `jobs` | lets the model run background jobs: `jobs_run`, `jobs_output`, `jobs_list`, `jobs_kill` |
| `task` | sub-agents in separate processes, optionally in isolated worktrees |
| `plan` | read-only plan mode |
| `mcp` | [MCP](project-files.md#mcp-servers) servers' tools |
| `skills` | skill discovery |
| `prompt-templates` | `/<name>` expansion |
| `context-files` | `AGENTS.md` (or `CLAUDE.md`), `.pijota/CONTEXT.md` |
| `security` | blocks `sudo`, `rm -rf`, `dd`, pipe-to-shell |
| `filter-output` | redacts secrets before they reach the model |
| `goal` | goal tracking and continuation |
| `schedule-prompt` | recurring and one-shot prompts |
| `btw` | `/btw` side questions, off the main thread |
| `inline-bash` | `!command` and `!!command` |
| `whimsical` | livelier working status text |

## Shipped, off

| Flag | Does |
|---|---|
| `--browse[=chrome\|firefox\|safari]` | browse rendered pages, extract readable Markdown |
| `--suggest[=<text\|file>]` | offer the next message as ghost text; Tab accepts |
| `--tmux` | supervise long-lived processes in tmux |
| `--todo` | structured todo list with a live panel |
| `--token-speed` | tokens/sec in the status bar |
| `--transcendence` | edit, build, test and respawn pijota's own source; JVM only |
| `--whoami` | tool reporting the running model and provider |

## Your own

```bash
pj -e ./my-extension.jar
```

| Location | Scope |
|---|---|
| `~/.pijota/extensions/` | every project |
| `.pijota/extensions/` | that project, once [trusted](project-files.md#trust) |

Implement the `Extension` SPI: `name()`, `description()`, `enabledByDefault()`, plus the hooks you
need. `enabledByDefault()` is a default; settings and flags still decide.

## Tools

Built-in, extension and MCP tools share one filter; [Tools](tools.md) lists the names.

```bash
pj -t read,grep,glob        # allowlist
pj -xt bash                 # denylist
pj --readonly               # read-only exploration
pj -nt                      # none
pj -nbt                     # no built-ins, keep extension tools
```
