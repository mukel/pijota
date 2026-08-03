# Settings

One JSON file, `~/.pijota/settings.json`. Every key is optional; the file may be absent.

`/settings` in the TUI edits the common ones and writes them back. Unknown keys are ignored, and
values pijota does not recognise produce a warning on stderr rather than a failure.

Move the whole directory with `PIJOTA_HOME` (or `-Dpijota.home=`).

```json
{
  "default_provider": "anthropic",
  "default_model": "claude-sonnet-4-20250514",
  "thinking_level": "medium",
  "theme": "gruvbox-dark"
}
```

## Model

| Key | Type | Default | Meaning |
|---|---|---|---|
| `default_provider` | string | - | provider id used when `--provider` is absent |
| `default_model` | string | - | model id used when `--model` is absent |
| `thinking_level` | string | `"medium"` | `off`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max` |
| `hide_thinking` | boolean | `false` | hide thinking blocks in the transcript |
| `enabled_models` | string[] | `[]` | allowlist for the model picker; empty means everything |
| `aliases` | object | `{}` | name → `provider/model`, usable as `--model alias/<name>` |
| `api_keys` | object | `{}` | provider id → key; `pj login` writes here |
| `providers` | array | `[]` | custom providers; see [Providers](providers.md) |
| `max_turns` | integer | `256` | tool-call turns per prompt; `0` is unlimited |

See [Models](models.md) for aliases and allowlists.

## Interface

| Key | Type | Default | Meaning |
|---|---|---|---|
| `theme` | string | - | theme name or path; unset follows the terminal |
| `themes` | string[] | `[]` | extra theme files to load |
| `markdown` | string | `"rich"` | `rich`, or `plain` for a minimal renderer |
| `path_display` | string | `"compact"` | `full`, `relative`, `compact`, `name` |
| `working_animation` | string | `"dots"` | `dots`, `moon`, `bar`, `braille`, `none` |
| `quiet_startup` | boolean | `false` | skip the startup header |
| `prompt` | object | - | prompt glyphs; see below |

### prompt

Every field is optional and falls back to the default.

| Field | Default | Is |
|---|---|---|
| `caret` | `❯` | the prompt caret |
| `error` | `✖` | caret when the last turn failed |
| `question` | `?` | caret while a tool is asking |
| `continuation` | `┃` | continuation bar on wrapped input |
| `bar` | `│` | rail beside multi-line input |
| `top_divider` | `true` | rule above the prompt |
| `bottom_divider` | `true` | rule below it |

```json
{ "prompt": { "caret": "›", "top_divider": false } }
```

## Shell

| Key | Type | Default | Meaning |
|---|---|---|---|
| `shell_path` | string | `$SHELL`, else `/bin/bash` (`bash` on `PATH` on Windows) | shell for the bash tool |
| `shell_command_prefix` | string | - | prepended to every command, on its own line |

`shell_command_prefix` is for setup the model should not have to repeat, such as sourcing an
environment file. It is not a sandbox.

## Storage

| Key | Type | Default | Meaning |
|---|---|---|---|
| `session_dir` | string | `~/.pijota/sessions` | where session files live |
| `project_dir` | string | git root of the cwd | project root for extensions and resources |
| `default_project_trust` | string | `"ask"` | `ask`, `always`, `never`; see [Project files](project-files.md) |

## Context

| Key | Type | Default | Meaning |
|---|---|---|---|
| `context_files` | boolean | `true` | load `AGENTS.md`, `CLAUDE.md`, `.pijota/CONTEXT.md` |
| `compaction` | object | see below | automatic context compaction |

### compaction

| Field | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | boolean | `true` | compact automatically as the window fills |
| `reserve_tokens` | integer | `16384` | headroom kept free for the next turn |
| `keep_recent_tokens` | integer | `20000` | recent conversation left uncompacted |

```json
{ "compaction": { "enabled": true, "reserve_tokens": 16384, "keep_recent_tokens": 20000 } }
```

## Extensions

`extensions` maps an extension name to `true`, `false`, or a value the extension interprets. This
is the persistent form of the `--<name>` / `--no-<name>` flags.

```json
{
  "extensions": {
    "suggest": "Suggest the next step, never a question.",
    "todo": true,
    "jobs": false
  }
}
```

See [Extensions](extensions.md).
