---
name: pijota
description: Configure and extend pijota itself -- its model, theme, tools, settings, agents, skills and self-modification. Load this whenever the user asks how pijota works, how to change it, where its files live, or how to add a skill, agent or theme.
---

You are running inside pijota and can inspect and change it -- this is about pijota, not the
user's project.

## First: most requests are a command, not a file edit

| The user wants | Answer |
|---|---|
| a settings menu | `/settings` |
| a different model | `/model`, or `/model <query>` |
| more or less reasoning | `Shift+Tab`, or start with `--thinking off\|minimal\|low\|medium\|high\|xhigh\|max` |
| a different theme | `/theme` -- lists the available ones and switches |
| to load this project's `AGENTS.md` and `.pijota/` | `/trust` |
| to stop the running turn | `Esc` (twice forces). `Ctrl+C` clears the line -- twice **exits** |
| to set the turn aside, still running | `Ctrl+B` twice -- it becomes a job; `/jobs`, `/kill <id>` |
| to resume a session | `-c` last, `-r` pick, `-s <id\|path>` |
| to know what is in context | `/context` |
| to shrink the context | `/compact` |
| fewer tools this run | `--readonly`, or `--tool <name>` / `--no-tool <name>` (repeatable) |
| an extension off this run | `--no-<extension>`, e.g. `--no-jobs` |

**`--no-tool <name>` turns off a tool; `--no-<name>` turns off an extension.** They are not
interchangeable: there is no `--no-bash`. An unknown flag is ignored without an error, so
the wrong one looks like it worked -- `pj --no-bash` leaves bash fully enabled. To take the
shell away use `--no-tool bash`, or `--readonly` for read-only tools only.

**Give the command, do not edit their configuration for them.** Editing `settings.json` when
`/model` would do it is slower and teaches them nothing repeatable.

**Commands change the running session; `settings.json` changes the default.** `/model`
switches the model for this session only -- it does not write `default_model`. Never tell the
user a command persists unless you have checked. When they say "permanently", set the key in
`settings.json` as well.

## Ask pijota instead of recalling

Flags, model ids and command names change. Check them:

- `/help` -- every command; `/context` -- what is in context, and how many tool schemas are
  really being sent. Both are in-process and always work.
- `pj --help` -- every flag; `pj --list-models` -- models you can actually reach (only
  providers with a usable credential). `pj` may be missing from PATH or be a stale wrapper; if
  it fails, that is that install, not this session. Answer from `/help` and `settings.json`.

Source and docs: <https://github.com/mukel/pijota> -- `docs/` has a page per topic
(`settings.md`, `cli.md`, `models.md`, `themes.md`, `tools.md`, `security.md`,
`troubleshooting.md`). They do not ship with the binary. Read them from the working tree when
it is pijota's own; the repo may be private, and a 404 there means you cannot reach it, not
that the answer does not exist -- fall back to `/help` and `settings.json`.

**Never say you checked something you did not.** Quoting help text from memory as "verified in
the source" is how stale answers survive: `--recover` resumes a session a *self-rebuild*
interrupted, not the last session -- that is `-c`.

## Where pijota keeps things

`~/.pijota/`, or the directory given by `-Dpijota.home=<dir>`:

| Path | Holds |
|---|---|
| `settings.json` | all configuration, including API keys under `api_keys`; owner-only |
| `auth.json` | OAuth tokens; owner-only |
| `themes/` `skills/` `agents/` `prompts/` `extensions/` | your own; a file here shadows a bundled one of the same name |
| `sessions/<flattened-cwd>/` | transcripts, one directory per project |
| `trust.json` | which project folders are trusted |
| `logs/pijota.log` | debug log, written when started with `--verbose` |

A project may carry the same kinds under `<project>/.pijota/`. They load only once the folder
is trusted, since they run code from that repository.

## Editing settings.json

Real keys:

`default_provider` `default_model` `api_keys` `aliases` `enabled_models` `theme` `themes`
`thinking_level` `max_turns` `context_files` `default_project_trust` `session_dir`
`project_dir` `shell_path` `shell_command_prefix` `hide_thinking` `quiet_startup`
`working_animation` `path_display` `markdown` `prompt` `providers` `extensions`
`compaction` (nested: `enabled`, `reserve_tokens`, `keep_recent_tokens`)

It fails quietly in two ways, so check your work:

- an unknown key is **ignored** -- a misspelled setting never applies
- an unrecognised *value* also falls back to a default: a typo in `thinking_level` becomes
  `medium` rather than an error (values are case-insensitive)
- invalid JSON falls back to **defaults** -- a broken edit reads as "my settings stopped
  working", not as an error

`read` the file (never write it from memory; it holds the user's API keys), `edit` one key,
read it back to confirm it parses, then say whether it applies now or on restart.

## Adding a skill, agent or theme

Write the file; it is picked up on the next start.

- **Skill** -- `~/.pijota/skills/<name>.md`, front-matter `name` and `description`. The
  description is all the model sees when deciding to load it, so write it as a routing rule
  ("Load this when..."), not a summary. **A blank description drops the skill silently.**
- **Sub-agent** -- `~/.pijota/agents/<name>.md`, front-matter `name`, `description`, optional
  `tools` (comma-separated) and `model`. Runs in its own process, with its own context.
- **Theme** -- `~/.pijota/themes/<name>.json`. Copy a bundled theme to start; a user file of
  the same name wins.

## Changing pijota itself

`--transcendence` lets pijota edit its own source, build, test and respawn into the new
binary. Off by default. When the user wants pijota to *behave* differently rather than be
*configured* differently, name that flag -- do not edit a running pijota's source unasked.
