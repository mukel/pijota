# Pijota User Docs

Short guides for real work.

Platform support: Linux and macOS are first-class. Windows works best through WSL; native Windows is best-effort and
expects Git Bash or a configured `shell_path` for shell tools.

## Start

```bash
jbang app install --native --name pj org.pijota:pijota:RELEASE
pj login          # OAuth where available, otherwise an API key
pj                # in a project directory
```

An API key in the environment works without `login`.

## Configuration

- [Command line](./cli.md): every flag, subcommand, and standalone command.
- [Settings](./settings.md): `settings.json`, key by key.
- [Providers](./providers.md): built-in providers, credentials, custom endpoints.
- [Models](./models.md): selecting models, aliases, allowlists, thinking levels.
- [Extensions](./extensions.md): what ships, how to toggle it, how to load your own.
- [Keybindings](./keybindings.md): shortcuts and `keybindings.json`.
- [Themes](./themes.md): shipped themes, file format, slots.
- [Sessions](./sessions.md): storage, resume, branching, export, compaction.
- [Project files](./project-files.md): trust, context files, skills, prompt templates, MCP.
- [Environment](./environment.md): variables pijota reads and variables it sets.

## Guides

- [JSON event stream](./json-mode.md): drive pijota from another program and follow a run live.
- [Terminal setup](./terminal-setup.md): make every modifier key work, and what to do when one doesn't.
- [Worktrees](./worktree.md): isolate risky changes in reusable git worktrees.
- [Release checklist](./release.md): publish the Maven Central artifact.

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
