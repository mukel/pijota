# Pijota User Docs

Reference and short guides.

Linux and macOS are first-class. Windows works best through WSL; native Windows expects Git Bash
or a configured `shell_path`.

## Start

```bash
jbang app install --native --name pj org.pijota:pijota:RELEASE
pj login          # OAuth where available, otherwise an API key
pj                # in a project directory
```

A provider key in the environment works without `login`.

## Configuration

- [Command line](./cli.md): flags, subcommands, standalone commands.
- [Slash commands](./commands.md): everything you can type after `/`.
- [Tools](./tools.md): what the model can do, and how to narrow it.
- [Settings](./settings.md): `settings.json`, key by key.
- [Providers](./providers.md): built-ins, credentials, custom endpoints.
- [Models](./models.md): selection, aliases, allowlists, thinking levels.
- [Extensions](./extensions.md): what ships, toggling, loading your own.
- [Keybindings](./keybindings.md): shortcuts and `keybindings.json`.
- [Themes](./themes.md): shipped themes, file format, slots.
- [Sessions](./sessions.md): storage, resume, branching, export, compaction.
- [Project files](./project-files.md): trust, context files, skills, prompt templates, MCP.
- [Environment](./environment.md): variables read and set.

## Guides

- [JSON event stream](./json-mode.md): follow a run event by event.
- [RPC mode](./rpc-mode.md): JSON-RPC over stdin/stdout.
- [Terminal setup](./terminal-setup.md): make every modifier key work.
- [Worktrees](./worktree.md): isolate risky changes.
- [Release checklist](./release.md): publish to Maven Central.

## Where things live

| Path | Holds |
|---|---|
| `~/.pijota/settings.json` | all settings |
| `~/.pijota/auth.json` | OAuth tokens and stored API keys |
| `~/.pijota/trust.json` | per-folder trust decisions |
| `~/.pijota/mcp.json` | MCP servers |
| `~/.pijota/keybindings.json` | key remaps |
| `~/.pijota/themes/`, `skills/`, `prompts/`, `extensions/` | user resources |
| `~/.pijota/sessions/<flattened-cwd>/` | session files |
| `~/.pijota/logs/` | logs |
| `.pijota/skills/`, `prompts/`, `extensions/`, `mcp.json` | per project, once trusted |

`PIJOTA_HOME` moves all of it.
