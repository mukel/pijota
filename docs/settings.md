# Settings

`~/.pijota/settings.json`. Every key optional; the file may be absent. `PIJOTA_HOME` (or
`-Dpijota.home=`) moves the directory.

`/settings` edits the common keys. Unknown keys are ignored; unrecognised values warn on stderr.

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
| `default_provider` | string | - | provider when `--provider` is absent |
| `default_model` | string | - | model when `--model` is absent |
| `thinking_level` | string | `"medium"` | `off`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max` |
| `hide_thinking` | boolean | `false` | hide thinking blocks |
| `enabled_models` | string[] | `[]` | picker allowlist; empty means all |
| `aliases` | object | `{}` | name → `provider/model`, used as `--model alias/<name>` |
| `api_keys` | object | `{}` | provider → key; `pj login` writes here |
| `providers` | array | `[]` | [custom providers](providers.md#custom-providers) |
| `max_turns` | integer | `256` | tool-call turns per prompt; `0` is unlimited |

## Interface

| Key | Type | Default | Meaning |
|---|---|---|---|
| `theme` | string | - | name or path; unset follows the terminal |
| `themes` | string[] | `[]` | extra theme files to load |
| `markdown` | string | `"rich"` | `rich`, or `plain` |
| `path_display` | string | `"compact"` | `full`, `relative`, `compact`, `name` |
| `working_animation` | string | `"dots"` | `dots`, `moon`, `bar`, `braille`, `none` |
| `quiet_startup` | boolean | `false` | skip the startup header |
| `prompt` | object | - | glyphs, below |

### prompt

| Field | Default | Is |
|---|---|---|
| `caret` | `❯` | prompt caret |
| `error` | `✖` | caret after a failed turn |
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
| `shell_command_prefix` | string | - | prepended to every command on its own line, e.g. to source an env file. Not a sandbox |

## Storage

| Key | Type | Default | Meaning |
|---|---|---|---|
| `session_dir` | string | `~/.pijota/sessions` | session files |
| `project_dir` | string | git root of the cwd | project root for resources |
| `default_project_trust` | string | `"ask"` | `ask`, `always`, `never`; see [trust](project-files.md#trust) |

## Context

| Key | Type | Default | Meaning |
|---|---|---|---|
| `context_files` | boolean | `true` | load `AGENTS.md`, `CLAUDE.md`, `.pijota/CONTEXT.md` |
| `compaction.enabled` | boolean | `true` | compact as the window fills |
| `compaction.reserve_tokens` | integer | `16384` | headroom kept for the next turn |
| `compaction.keep_recent_tokens` | integer | `20000` | recent conversation left uncompacted |

```json
{ "compaction": { "enabled": true, "reserve_tokens": 16384, "keep_recent_tokens": 20000 } }
```

## Extensions

Persistent form of `--<name>` / `--no-<name>`. A value is `true`, `false`, or a string the
extension reads. [Extensions](extensions.md).

```json
{
  "extensions": {
    "suggest": "Suggest the next step, never a question.",
    "todo": true,
    "jobs": false
  }
}
```
