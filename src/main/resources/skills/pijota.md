---
name: pijota
description: How to inspect and change pijota itself -- its settings, model, theme, tools, extensions, agents and skills. Load this when the user asks how pijota works, wants to configure or customise it, asks "how do I make pijota do X", or wants to add an agent, skill or theme.
---

You are running *inside* pijota, so you can inspect and change it directly. Everything below
is about the tool you are running in, not about the user's project.

## The rule that matters

**Show the user the command; do not silently rewrite their configuration.**

Most of what people want is a slash command away and takes effect immediately. Reaching for
`settings.json` when `/model` would have done it is worse for the user: it is slower, it is
permanent when they wanted it for one session, and they learn nothing they can repeat.

Edit files when the user asks for a *persistent* change, or when there is no command for it.
Say which file you changed and whether a restart is needed.

## Ask pijota, do not guess

Pijota can describe itself. Prefer these over recalling anything:

| Question | How to answer it |
|---|---|
| what flags exist | `bash: pj --help` |
| what commands exist | tell the user to press `/`, or read `/help` |
| what models are reachable | `bash: pj --list-models` (only lists providers with a usable credential) |
| what is in context now | tell the user `/context` |
| what a setting is called | read `~/.pijota/settings.json`, or the table below |
| what tools are active | `/context` counts the tool schemas actually sent |

If the working directory is pijota's own source, `docs/` has a page per topic: `settings.md`,
`cli.md`, `commands.md`, `models.md`, `providers.md`, `extensions.md`, `themes.md`, `tools.md`,
`keybindings.md`, `sessions.md`, `security.md`, `troubleshooting.md`. Read them before
answering from memory. They do not ship with the binary, so check the file exists first.

## Where pijota keeps things

`~/.pijota/` (override with `-Dpijota.home=<dir>`):

| Path | Holds |
|---|---|
| `settings.json` | all configuration; API keys under `api_keys`; owner-only permissions |
| `auth.json` | OAuth tokens; owner-only |
| `themes/` | user themes; a file here shadows a bundled theme of the same name |
| `skills/` | user skills, like this one |
| `agents/`, `prompts/`, `extensions/` | sub-agents, prompt templates, extensions |
| `sessions/<flattened-cwd>/` | transcripts, one directory per project |
| `trust.json` | which project folders are trusted |

A project can carry the same kinds under `<project>/.pijota/`. Project resources only load
when the folder is trusted (`/trust`), because they run code from that repository.

## Settings worth knowing

Real keys from `settings.json`. Unknown keys are ignored, so a typo fails silently -- read the
file back after editing.

`default_provider`, `default_model`, `api_keys`, `aliases`, `enabled_models`, `theme`,
`themes`, `thinking_level`, `max_turns`, `compaction` (`keep_recent_tokens`, `reserve_tokens`),
`context_files`, `default_project_trust`, `session_dir`, `project_dir`, `shell_path`,
`shell_command_prefix`, `hide_thinking`, `quiet_startup`, `working_animation`, `path_display`,
`markdown`, `prompt`, `providers`, `extensions`.

Editing safely:

1. `read` the file first -- never write it from memory, it holds the user's API keys.
2. Change one key with `edit`.
3. Read it back and confirm the JSON still parses.
4. Say whether it applies now or on restart.

A settings file that is invalid JSON falls back to defaults rather than failing, so a broken
edit looks like "my settings stopped working" rather than an error. Check your work.

## Changing behaviour without editing anything

- model: `/model` (picker) or `/model <query>`
- thinking effort: `Shift+Tab`, or `--thinking off|minimal|low|medium|high|xhigh|max`
- theme: `/theme`, or `--theme <name|path>`
- tools: `--readonly`, `--tool <name>`, `--no-tool <name>` (repeatable)
- extensions: `--no-<name>` turns one off for the run, e.g. `--no-jobs`
- trust: `/trust`

## Adding to pijota

All three are markdown with YAML front-matter. Write the file, then tell the user it is
picked up on the next start.

**A skill** -- `~/.pijota/skills/<name>.md`, front-matter `name` and `description`. The
description is the only thing the model sees when deciding whether to load it, so write it as
a routing rule ("Load this when...") rather than a summary. A skill with no description is
silently ignored.

**A sub-agent** -- `~/.pijota/agents/<name>.md`, front-matter `name`, `description`, optional
`tools` (comma-separated) and `model`. It runs in its own process with its own context.

**A theme** -- `~/.pijota/themes/<name>.json`. Copying a bundled theme and editing it is the
reliable start; a user file shadows a bundled name.

## Pijota can rebuild itself

With `--transcendence`, pijota can edit its own source, build, test and respawn into the new
binary. It is off by default and works in an isolated git worktree. If the user wants pijota
to change its own behaviour rather than its configuration, that is the route -- tell them the
flag rather than editing the source of a running pijota by surprise.

## When something looks broken

`--verbose` writes debug logging to `~/.pijota/logs/pijota.log`. `pj --license` proves the
binary carries its embedded resources, which is the usual suspect when something works from
source and not from a release build.
