# Extensions

Extensions add tools, slash commands, keybindings, status lines, prompt context and safety hooks.
Pijota ships a set; you can load more from disk.

## Turning them on and off

Three ways, from most to least local:

```bash
pj --todo              # on for this run
pj --no-jobs           # off for this run
```

```json
{ "extensions": { "todo": true, "jobs": false } }
```

Some take a value, in which case `--name=value` and a string in settings mean the same thing:

```bash
pj --browse=firefox
pj --suggest="Suggest the next step, never a question."
pj --suggest=~/prompts/suggest.md      # a readable path is read as the prompt
```

Blanket switches:

| Flag | Effect |
|---|---|
| `--no-extensions`, `-ne` | nothing loads, shipped or discovered |
| `--no-builtin-extensions`, `-nbe` | only discovered extensions load |

## Shipped

On by default:

| Name | Does |
|---|---|
| `jobs` | background shell jobs: `jobs_run`, `jobs_output`, `jobs_list`, `jobs_kill`, and Ctrl+B twice to background a running command |
| `task` | sub-agents in separate processes, optionally in isolated worktrees |
| `plan` | read-only plan mode for safe exploration |
| `mcp` | bridges [MCP](project-files.md#mcp-servers) servers' tools into the session |
| `skills` | skill discovery; `--no-skills` disables |
| `prompt-templates` | `/<name>` template expansion; `--no-prompt-templates` disables |
| `context-files` | `AGENTS.md`, `CLAUDE.md`, `.pijota/CONTEXT.md`; `--no-context-files` disables |
| `security` | blocks `sudo`, `rm -rf`, `dd`, pipe-to-shell |
| `filter-output` | redacts secrets and tokens before they reach the model |
| `goal` | goal tracking and continuation |
| `schedule-prompt` | recurring and one-shot scheduled prompts |
| `btw` | `/btw` side questions that do not disturb the main thread |
| `inline-bash` | `!command` and `!!command` from the prompt |
| `whimsical` | replaces the working status text with something less dull |

Off by default:

| Flag | Does |
|---|---|
| `--browse[=chrome\|firefox\|safari]` | browse rendered pages through a real browser and extract readable Markdown |
| `--suggest[=<text\|file>]` | after a turn, offer the next message as ghost text; Tab accepts |
| `--tmux` | run and supervise long-lived processes in tmux |
| `--todo` | structured todo list with a live panel |
| `--token-speed` | tokens/sec in the status bar |
| `--transcendence` | self-modification: edit, build, test and respawn pijota's own source (JVM only) |
| `--whoami` | a tool that reports the running model and provider |

`--transcendence` is JVM-only: the native binary cannot rebuild itself.

## Loading your own

```bash
pj -e ./my-extension.jar
```

Discovered automatically:

| Location | Scope |
|---|---|
| `~/.pijota/extensions/` | every project |
| `.pijota/` in the project | that project, once trusted |

Project-local extensions are code, so they only load for a folder you have trusted. See
[Project files](project-files.md#trust).

An extension implements the `Extension` SPI: `name()`, `description()`, `enabledByDefault()`,
plus whichever hooks it needs. `enabledByDefault()` is only the default -- settings and flags
still decide.

## Tools, separately

Tools come from built-ins, extensions and MCP servers alike, and the same flags filter all three:

```bash
pj -t read,grep,glob        # allowlist
pj -xt bash                 # denylist
pj --readonly               # read-only exploration tools only
pj -nt                      # no tools
pj -nbt                     # no built-ins, keep extension tools
```
