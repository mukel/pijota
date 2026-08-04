---
name: pijota
description: Configure and extend pijota itself -- its model, theme, tools, settings, agents, skills and self-modification. Load this whenever the user asks how pijota works, how to change it, where its files live, or how to add a skill, agent or theme.
---

You are running inside pijota and can inspect and change it. This is about pijota itself.

## Never handle the user's secrets

`settings.json` and `auth.json` hold API keys and OAuth tokens. **Reading a file sends it to
the LLM service**, not just to the screen, so a credential question is answered *about* the
key, never *from* it:

- allowed: "a key is set for deepseek" / "no usable credential for openai"
- forbidden: the value, a prefix, its length, its shape, or pasting the file back
- to check: `pj --list-models` lists only providers with a usable credential; or match key
  **names**, never values. `edit` changes a key without reading it.
- to add one: `pj login [provider]`, which never puts the key in front of you or the model.
  Hand-editing `api_keys` is the fallback, not the advice.

`filter-output` redacts known secret shapes on the way in. It is a backstop for accidents,
not permission to go looking.

## First: most requests are a command, not a file edit

| The user wants | Answer |
|---|---|
| a settings menu | `/settings` |
| a different model | `/model`, or `/model <query>`; `-m <id>` at startup. Any id is sent to the provider verbatim -- the catalog only feeds the picker |
| to add a provider credential | `pj login [provider]` -- OAuth where the provider offers it, otherwise it asks for the key and stores it. `pj logout <provider>` forgets it |
| more or less reasoning | `Shift+Tab`, or start with `--thinking off\|minimal\|low\|medium\|high\|xhigh\|max` |
| a different theme | `/theme` -- lists the available ones and switches |
| to load this project's `AGENTS.md` and `.pijota/` | `/trust`; `-a` for one run; `"default_project_trust": "always"` to stop being asked (`"ask"` default, `"never"` refuses) |
| to stop the running turn | `Esc` (twice forces). `Ctrl+C` clears the line -- twice **exits** |
| to set the turn aside, still running | `Ctrl+B` twice, or `/bg` -- inside tmux the chord is the tmux prefix, so use the command; it becomes a job (`/jobs`, `/kill <id>`) |
| to take back the last turn | `/undo` -- drops it and puts the text back in the prompt to edit and resend |
| to go further back | `/tree` -- pick any earlier entry and jump there; an undone turn is still in the tree, so this reaches it |
| to resume a session | `-c` last, `-r` pick, `-s <id\|path>` |
| sub-agents on a cheaper model | `PIJOTA_SUBAGENT_MODEL=<provider/model>`; a `model:` in the agent's own front-matter wins |
| to know what is in context | `/context`; `/usage` for tokens and cost |
| to shrink the context | `/compact` |
| to share a conversation | `/export` (Markdown), `/export html`, or `--export <file>` |
| to open the running session in a browser | `/remote-control` |
| to start fresh | `/new` |
| a model the picker does not list | `/refresh` (or `pj --refresh-models`) asks each provider what it serves and remembers it; needed once for a local/keyless provider, since nothing else triggers it |
| to see the loaded skills | `/skills` |
| fewer tools this run | `--readonly`, or `--tool <name>` / `--no-tool <name>` (repeatable) |
| an extension off this run | `--no-<extension>`, e.g. `--no-jobs` |

**`--no-tool <name>` turns off a tool; `--no-<name>` turns off an extension.** They are not
interchangeable: there is no `--no-bash`, and pijota says so (`unknown option '--no-bash'
(no such extension); it does nothing`). To take the shell away use `--no-tool bash`, or
`--readonly` for read-only tools only.

**Give the command; do not edit their configuration for them.** It is faster and they can
repeat it.

**A command changes the running session; `settings.json` changes the default.** `/model`
switches the model for this session only -- it does not write `default_model`. Never claim a
command persists unless you have checked. When the user says "permanently", set the key too.

## Other ways to run it

`pj web [host:port]` browser UI · `pj acp` in an editor (Zed, JetBrains) · `pj telegram` bot ·
`pj -p "..."` one shot for scripts, with `--mode json` for machine-readable events ·
`--browse` browser automation · `-a` trusts this folder for the run without asking (`-na`
refuses) · `pj worktree` a reusable pool of git worktrees.

## Ask pijota instead of recalling

Flags, model ids and command names change. Check them:

- `/help` -- every command; `/context` -- what is in context, and how many tool schemas are
  really being sent. Both are in-process and always work.
- `pj --help` -- every flag; `pj --list-models` -- models you can actually reach (only
  providers with a usable credential). `pj` may be missing from PATH or be a stale wrapper; if
  it fails, that is that install, not this session. Answer from `/help` and `settings.json`.

Source and docs: <https://github.com/mukel/pijota> -- `docs/` has a page per topic
(`settings.md`, `cli.md`, `tools.md`, `security.md`, `troubleshooting.md`, ...), which do not
ship with the binary. Read them from the working tree when it is pijota's own. A 404 means you
cannot reach the repo, not that the answer does not exist -- fall back to `/help`.

**Never say you checked something you did not.** Quoting remembered help text as "verified in
the source" is how stale answers survive: `--recover` resumes a session a *self-rebuild*
interrupted -- the last session is `-c`.

## Where pijota keeps things

`~/.pijota/`, or the directory given by `-Dpijota.home=<dir>`:

| Path | Holds |
|---|---|
| `settings.json` | all configuration, including API keys under `api_keys`; owner-only |
| `auth.json` | OAuth tokens; owner-only |
| `themes/` `skills/` `agents/` `prompts/` `extensions/` | your own. A file here shadows a bundled one of the same name, so `agents/explore.md` replaces the built-in `explore` |
| `sessions/<flattened-cwd>/` | transcripts, one directory per project |
| `trust.json` | which project folders are trusted |
| `logs/pijota.log` | debug log, written when started with `--verbose` |

A project may carry the same kinds under `<project>/.pijota/`, plus `mcp.json`. None of them
load until the folder is trusted, since they run code, or instructions, from that repository.

## Editing settings.json

The keys (anything else is ignored, so a typo silently never applies). Several that read like
booleans are strings -- `default_project_trust` is `"ask"`/`"always"`/`"never"`, `thinking_level`
is a level name -- so check the current value before writing a new one:

`default_provider` `default_model` `api_keys` `aliases` `enabled_models` `theme` `themes`
`thinking_level` `max_turns` `context_files` `default_project_trust` `session_dir`
`project_dir` `shell_path` `shell_command_prefix` `hide_thinking` `quiet_startup`
`working_animation` `path_display` `markdown` `prompt` `providers` `extensions`
`compaction` (nested: `enabled`, `reserve_tokens`, `keep_recent_tokens`)

It fails quietly, so check your work:

- an unrecognised *value* falls back to a default: a typo in `thinking_level` becomes
  `medium` rather than an error (values are case-insensitive)
- invalid JSON falls back to **defaults** -- a broken edit reads as "my settings stopped
  working", not as an error

`read` it to see the current value, `edit` one key, read it back to confirm it still parses,
then say whether the change applies now or on restart. Never write the file wholesale from
memory, and for anything under `api_keys` skip the read -- `edit` alone.

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
