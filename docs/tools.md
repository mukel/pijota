# Tools

What the model can do. Names here are the ones `--tools` and `--exclude-tools` take.

```bash
pj -t read,grep,glob        # allowlist
pj -xt bash                 # denylist
pj --readonly               # the read-only set, below
pj -nt                      # none
pj -nbt                     # no built-ins, keep extension tools
```

One filter covers built-in, extension and MCP tools alike. `/context` shows what the tool schemas
cost in the window.

## Built in

| Tool | Does |
|---|---|
| `read` | read a file; 2000 lines and 50KB by default |
| `write` | write a file, creating or overwriting |
| `edit` | exact-text replacement; each `oldText` must match once |
| `apply_patch` | apply a unified diff across one or more files |
| `ls` | list one directory, not recursively |
| `glob` | find files by glob pattern |
| `grep` | search file contents, with paths and line numbers |
| `bash` | run a shell command; see [shell settings](settings.md#shell) |
| `webfetch` | fetch a URL as markdown, text or html |
| `websearch` | search the web; needs `EXA_API_KEY` or `PARALLEL_API_KEY` |
| `question` | ask the user and wait for the answer |

## From extensions

Present only when the extension is on ([Extensions](extensions.md)).

| Tool | Extension |
|---|---|
| `task` | `task`: a sub-agent in its own process |
| `skill` | `skills`: load a skill's instructions on demand |
| `jobs_run`, `jobs_output`, `jobs_list`, `jobs_kill` | `jobs` |
| `goal_set`, `goal_complete` | `goal` |
| `schedule_prompt` | `schedule-prompt` |
| `todo` | `--todo` |
| `whoami` | `--whoami` |
| `web_visit_page`, `web_google_search`, `web_pdf_to_markdown` | `--browse` |
| `tmux_bash`, `tmux_send`, `tmux_capture`, `tmux_list`, `tmux_name`, `tmux_kill` | `--tmux` |
| `transcendence` | `--transcendence` |

MCP servers add theirs under their own names; see [MCP](project-files.md#mcp-servers).

## Sub-agents

`task` spawns a separate process, so each one is visible and interruptible:

A sub-agent is a process like any other, so it is listed and stopped like any other -- there is
no separate agent vocabulary:

| | |
|---|---|
| status bar | one line per agent while it runs: last tool, tokens so far, lines, elapsed |
| [`/jobs`](#sub-agents) | every running process with an id, marked `in turn` or `background` |
| `/kill <id>` | stops it and its own children (its bash, its browser) |
| `Esc` | stops every sub-agent in the turn, along with the turn |
| `Ctrl+B` twice | hands it to the background, and the turn resumes |
| `/logs` | the full transcript, tailing while it runs |

A stopped agent is reported to the model as your decision, not a crash, so it is not
started again on its own.

Launching the same agent twice at once suffixes the second: `explore`, `explore#2`. Each
gets its own row, log and stop.

The id is issued when the agent starts and does not change when you background it. Its
output is delivered when it finishes -- no polling. Only a single agent can be
backgrounded; a parallel batch declines, because resolving the call mid-batch would
discard the other agents' results.

A stopped agent stays stopped: it will not be started again for the rest of that turn,
whatever the model decides.

Sub-agents do not nest. A sub-agent gets no `task` tool, because each level multiplies
fan-out and every leaf spends tokens: two levels of four is sixteen agents from one call.

## Read-only

`--readonly` allows exactly:

`read`, `ls`, `grep`, `glob`, `webfetch`, `websearch`, `question`, `whoami`

Nothing that writes a file, runs a command, or spawns a process. `--tools` still narrows further.

## Guard rails

Independent of the filters, and on by default:

| Extension | Refuses |
|---|---|
| `security` | `sudo`, `rm -rf`, `dd`, pipe-to-shell |
| `filter-output` | sends secrets and tokens to the model |

Neither is a sandbox. Pijota runs with your user's permissions; use a container, VM or
[worktree](worktree.md) when you need a real boundary.
