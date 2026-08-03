# Command line

```
pijota [SUBCOMMAND] [OPTIONS] [PROMPT...]
```

`pj` is the installed name; `pijota` works too. With no prompt and no subcommand, pijota starts the
interactive TUI. A prompt on the command line, or piped stdin, runs one turn and exits.

```bash
pj                                  # interactive
pj -p "explain this repo"           # one shot
echo "explain this repo" | pj       # same, from stdin
pj -c                               # continue the last session
```

## Frontends

The default is the TUI. Each frontend is a positional subcommand; only `web` takes a value.

| Subcommand | Runs |
|---|---|
| `tui` | interactive terminal UI (the default) |
| `web [host:port]` | local web UI, with a QR code for phone pairing |
| `telegram` | Telegram bot; needs `TELEGRAM_BOT_TOKEN` |
| `acp` | Agent Client Protocol over stdio, for Zed and JetBrains |

Put the subcommand before its flags. A value-optional flag will not swallow it
(`pj --suggest web 127.0.0.1:8080` starts the web UI), but the documented way to give a flag a
value is `--flag=value`.

## Options

### Model

| Flag | Effect |
|---|---|
| `--provider <name>` | provider id, e.g. `anthropic` |
| `--model, -m <id>` | model id, or `provider/id`, or `alias/<name>` |
| `--api-key <key>` | key for this run, for the resolved provider only |
| `--thinking <level>` | `off`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max` |
| `--list-models [filter]` | print available models and exit |

### Prompt and turns

| Flag | Effect |
|---|---|
| `--print, -p [prompt]` | non-interactive single shot; reads stdin if no prompt |
| `--mode <mode>` | `text` (default), `json` ([events](json-mode.md)), `rpc` (JSON-RPC over stdio) |
| `--system-prompt <text>` | replace the system prompt |
| `--append-system-prompt <text>` | append to it; repeatable |
| `--max-turns <n>` | tool-call turns per prompt; `0` is unlimited |

### Sessions

| Flag | Effect |
|---|---|
| `--session, -s <id>` | session file path or partial UUID |
| `--continue, -c` | continue the most recent session |
| `--resume, -r` | interactive session picker |
| `--recover` | recover the last interrupted session |
| `--fork <id>` | fork a session into a new one |
| `--no-session` | do not write a session file |
| `--export <file>` | export the session to Markdown and exit |

See [Sessions](sessions.md).

### Tools

| Flag | Effect |
|---|---|
| `--tool, -t <names>` | allowlist; comma-separated, any source |
| `--no-tool, -xt <names>` | denylist; comma-separated, any source |
| `--no-tools, -nt` | no tools at all |
| `--readonly`, `--read-only` | read-only exploration tools only |
| `--no-builtin-tools, -nbt` | drop built-in tools, keep extension tools |

The lists compose: registration is unconditional and these flags filter.

### Resources

| Flag | Effect |
|---|---|
| `--extension, -e <path>` | load an extension; repeatable |
| `--no-extensions, -ne` | disable all extensions |
| `--no-builtin-extensions, -nbe` | disable shipped extensions, keep discovered ones |
| `--skill <path>` | load a skill; repeatable |
| `--no-skills, -ns` | skip skill discovery |
| `--prompt-template <path>` | load a prompt template; repeatable |
| `--no-prompt-templates, -np` | skip prompt-template discovery |
| `--theme <path\|name>` | load or select a theme; repeatable; `auto` follows the terminal |
| `--no-themes` | skip theme discovery |
| `--no-context-files, -nc` | skip `AGENTS.md` / `CLAUDE.md` discovery |
| `--approve, -a` | trust project-local resources for this run |
| `--no-approve, -na` | ignore them for this run |

See [Project files](project-files.md).

### Extensions

`--<name>` enables, `--no-<name>` disables. Both persist under `extensions` in
[settings](settings.md) when set there instead. Full list in [Extensions](extensions.md).

| Flag | Default |
|---|---|
| `--browse[=chrome\|firefox\|safari]` | off |
| `--suggest[=<text\|file>]` | off |
| `--tmux` | off |
| `--todo` | off |
| `--token-speed` | off |
| `--transcendence` | off (JVM only) |
| `--whoami` | off |
| `--no-jobs` | on |
| `--no-skills`, `--no-prompt-templates`, `--no-context-files` | on |

### Misc

| Flag | Effect |
|---|---|
| `--verbose` | debug logging to `~/.pijota/logs` |
| `--help, -h` | help |
| `--version, -v` | version |
| `--license` | pijota's license plus every embedded third-party notice |

## Commands

These come before any options and do not start an agent.

| Command | Does |
|---|---|
| `login [provider] [model]` | sign in: OAuth where the provider supports it, otherwise an API key |
| `logout [provider]` | forget a stored credential |
| `auth <cmd> --provider <p>` | print a credential for another tool: `print-api-key`, `print-bearer-token` |
| `worktree <cmd>` | manage git worktrees; see [Worktrees](worktree.md) |

`pj auth --help` and `pj worktree --help` have their own detail.
