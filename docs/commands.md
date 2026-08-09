# Slash commands

Type `/` for completion, `Ctrl+P` for a palette over everything. `/help` prints this list;
`/hotkeys` prints the [keys](keybindings.md).

Prompt templates become commands too, one per `.md` file: see
[project files](project-files.md#prompt-templates).

## Chat

| Command | Does |
|---|---|
| `/help` | commands and key bindings |
| `/model` | select or search models |
| `/settings` | settings menu |
| `/compact` | compact the conversation now |
| `/branch-summary` | summarise the current branch into context |
| `/context` | token usage by category |
| `/usage` | cumulative tokens and cost |
| `/dequeue` | put a queued follow-up back in the editor |
| `/skills [name]` | list skills |
| `/prompts` | list prompt templates |

## Session

| Command | Does |
|---|---|
| `/sessions` | pick a session; `Enter` loads, `Ctrl+X` deletes |
| `/save [path]` | save to a file |
| `/load <id\|path>` | load and resume |
| `/delete <id\|path>` | delete |
| `/export [md\|html] [path]` | export |
| `/import <path>` | import a JSONL session and resume |
| `/name <name>` | set the display name |
| `/new` | start over |

## Workspace

| Command | Does |
|---|---|
| `/tree` | browse the conversation tree and jump |
| `/fork [message]` | fork from an earlier message |
| `/undo` | edit the previous message |
| `/copy` | copy the last response |
| `/theme` | list or switch themes |
| `/jobs` | every running process with an id, `in turn` or `background` |
| `/kill <id>` | stop one, whatever kind it is |
| `/trust` | trust this folder's project files |
| `/plan` | toggle read-only plan mode |
| `/bg` | background the running tool |

## Account

| Command | Does |
|---|---|
| `/login` | sign in to a provider |
| `/logout` | forget a credential |
| `/refresh` | refresh the model list |
| `/reload` | reload themes, skills, prompts, extensions |
| `/hotkeys` | keyboard shortcuts |
| `/quit` | exit |

## Remote

| Command | Does |
|---|---|
| `/remote-control` | drive this session from a phone or browser |

## From extensions

Present only when the extension is on ([Extensions](extensions.md)).

| Command | Extension |
|---|---|
| `/btw`, `/btw:tangent`, `/btw:inject` | `btw`: a read-only side question, off the main thread |
| `/goal <objective>`, `/loop <objective>` | `goal`: track or auto-continue a goal |
| `/logs` | `task`: the latest sub-agent log |
| `/mcp` | `mcp`: list, connect, disconnect, reconnect, reload |
| `/schedule-prompt` | `schedule-prompt` |
| `/security` | `security`: toggle dangerous-command blocking |
| `/filter-output` | `filter-output`: toggle secret redaction |
| `/whimsical` | `whimsical`: toggle the status phrases |
| `/supervise`, `/tmux-list`, `/tmux-send`, `/tmux-capture` | `--tmux` |
| `/transcend` | `--transcendence` |

## Shell, not a command

| Input | Runs |
|---|---|
| `!command` | a shell command; output goes to the model |
| `!!command` | the same, hidden from the model |
| `@path` | fuzzy file reference inserted into the prompt |
