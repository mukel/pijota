# Sessions

Every run writes an append-only JSONL file unless you pass `--no-session`.

```
~/.pijota/sessions/<flattened-cwd>/<uuid>.jsonl
```

Grouped by project: the directory is the working directory with separators replaced.
`session_dir` moves the root.

## Resuming

```bash
pj -c                    # continue the most recent session here
pj -r                    # pick one interactively
pj -s 3f2a               # a partial UUID is enough
pj -s ./path/to.jsonl    # or a path
pj --recover             # recover the last interrupted session
```

In the TUI: `/sessions` to pick (`Enter` loads, `Ctrl+D` deletes), `/load`, `/save`, `/delete`,
`/name <name>`, `/new`.

## Branching

The file is a tree, so one session can hold several branches. Jumping to an earlier entry and
continuing creates one; nothing is overwritten.

| Command | Does |
|---|---|
| `/tree` | browse every entry and jump to one |
| `/fork [message]` | new session starting from an earlier message |
| `/undo` | edit the previous message and continue from there |
| `--fork <id>` | fork from the command line |

## Exporting

```bash
pj --export out.md
```

| Command | Writes |
|---|---|
| `/export` | Markdown |
| `/export html [path]` | a styled HTML page, using the theme's `export` colours |
| `/import <path>` | read a JSONL session back in and resume it |
| `/copy` | the last assistant message, to the clipboard |

## Compaction

When the window fills, the older conversation is summarised and the run continues.

| Setting | Default | Meaning |
|---|---|---|
| `compaction.enabled` | `true` | compact automatically |
| `compaction.reserve_tokens` | `16384` | headroom kept free for the next turn |
| `compaction.keep_recent_tokens` | `20000` | recent conversation left untouched |

`/compact` runs it now; `/branch-summary` summarises the branch without discarding it;
`/context` breaks the window down by category and `/usage` reports tokens and cost.

The summary is its own session entry, so a resumed session sees the context the live one did.

## Crash safety

A torn final line is repaired on the next open, so a crash costs at most the turn in flight.
Appends are durable against a process crash; compaction also syncs to disk.
