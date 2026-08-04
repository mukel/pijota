# Command line

```
pijota [SUBCOMMAND] [OPTIONS] [PROMPT...]
```

`pj` is the installed name. No prompt starts the TUI; a prompt or piped stdin runs one turn.

```bash
pj                                  # interactive
pj -p "explain this repo"           # one shot
echo "explain this repo" | pj       # from stdin
pj -c                               # continue the last session
```

## Frontends

Positional, before the flags. Only `web` takes a value.

| Subcommand | Runs |
|---|---|
| `tui` | terminal UI (default) |
| `web [host:port]` | local web UI, with a QR code for phone pairing |
| `telegram` | Telegram bot; needs `TELEGRAM_BOT_TOKEN` |
| `acp` | Agent Client Protocol over stdio, for Zed and JetBrains |

Give a flag a value with `--flag=value`.

## Model

| Flag | Effect |
|---|---|
| `--provider <name>` | provider id |
| `--model, -m <id>` | model id, `provider/id`, or `alias/<name>` |
| `--api-key <key>` | key for this run, this provider |
| `--thinking <level>` | `off`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max` |
| `--list-models [filter]` | list models and exit |

## Prompt and turns

| Flag | Effect |
|---|---|
| `--print, -p [prompt]` | single shot; reads stdin if no prompt |
| `--mode <mode>` | `text` (default), `json` ([events](json-mode.md)), `rpc` ([JSON-RPC](rpc-mode.md)) |
| `--system-prompt <text>` | replace the system prompt |
| `--append-system-prompt <text>` | append to it; repeatable |
| `--max-turns <n>` | tool-call turns per prompt; `0` is unlimited |

## Sessions

| Flag | Effect |
|---|---|
| `--session, -s <id>` | session path or partial UUID |
| `--continue, -c` | continue the most recent session |
| `--resume, -r` | session picker |
| `--recover` | resume a session a self-rebuild interrupted ([transcendence](cli.md)); for an ordinary session use `-c` or `-r` |
| `--fork <id>` | fork into a new session |
| `--no-session` | do not write a session file |
| `--export <file>` | export to Markdown and exit |

[Sessions](sessions.md).

## Tools

| Flag | Effect |
|---|---|
| `--tool, -t <names>` | allowlist, comma-separated |
| `--no-tool, -xt <names>` | denylist, comma-separated |
| `--no-tools, -nt` | no tools |
| `--readonly`, `--read-only` | read-only exploration tools |
| `--no-builtin-tools, -nbt` | drop built-ins, keep extension tools |

Lists apply to built-in, extension and MCP tools alike, and compose. [Tools](tools.md) names them.

## Resources

| Flag | Effect |
|---|---|
| `--extension, -e <path>` | load an extension; repeatable |
| `--no-extensions, -ne` | disable all extensions |
| `--no-builtin-extensions, -nbe` | keep only discovered extensions |
| `--skill <path>` | load a skill; repeatable |
| `--no-skills, -ns` | skip skill discovery |
| `--prompt-template <path>` | load a template; repeatable |
| `--no-prompt-templates, -np` | skip template discovery |
| `--theme <path\|name>` | load or select a theme; `auto` follows the terminal |
| `--no-themes` | skip theme discovery |
| `--no-context-files, -nc` | skip `AGENTS.md` / `CLAUDE.md` context |
| `--approve, -a` | trust project-local resources this run |
| `--no-approve, -na` | ignore them this run |

[Project files](project-files.md).

## Extensions

`--<name>` on, `--no-<name>` off; persistent under `extensions` in [settings](settings.md).

| Flag | Default |
|---|---|
| `--browse[=chrome\|firefox\|safari]` | off |
| `--suggest[=<text\|file>]` | off |
| `--tmux` | off |
| `--todo` | off |
| `--token-speed` | off |
| `--transcendence` | off, JVM only |
| `--whoami` | off |
| `--no-jobs` | on -- takes the model's `jobs_*` tools; `/jobs`, `/kill` and Ctrl+B stay |
| `--no-skills`, `--no-prompt-templates`, `--no-context-files` | on |

[Extensions](extensions.md).

## Misc

| Flag | Effect |
|---|---|
| `--verbose` | debug logging to `~/.pijota/logs` |
| `--help, -h` | help |
| `--version, -v` | version |
| `--license` | license and third-party notices |

## Commands

Before any options; these do not start an agent.

| Command | Does |
|---|---|
| `login [provider] [model]` | OAuth where supported, otherwise an API key |
| `logout [provider]` | forget a credential |
| `auth print-api-key --provider <p>` | print a stored key |
| `auth print-bearer-token --provider <p>` | print an OAuth access token |
| `worktree <cmd>` | [git worktrees](worktree.md) |
