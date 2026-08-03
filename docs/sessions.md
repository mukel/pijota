# Sessions

Every run writes an append-only JSONL file, one entry per line, unless you pass `--no-session`.

## Where

```
~/.pijota/sessions/<flattened-cwd>/<uuid>.jsonl
```

The directory name is the working directory with separators replaced, so sessions are grouped by
project. `session_dir` in [settings](settings.md) moves the root.

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

The file is a tree, not a list, so a session can hold several branches.

| Command | Does |
|---|---|
| `/tree` | browse every entry and jump to one |
| `/fork [message]` | new session starting from an earlier message |
| `/undo` | edit the previous message and continue from there |
| `--fork <id>` | fork from the command line |

Jumping to an earlier entry and continuing creates a branch; nothing is overwritten.

## Exporting

```bash
pj --export out.md                  # from the command line
```

| Command | Writes |
|---|---|
| `/export` | Markdown |
| `/export html [path]` | a styled HTML page, using the theme's `export` colours |
| `/import <path>` | read a JSONL session back in and resume it |
| `/copy` | the last assistant message, to the clipboard |

## Compaction

When the context window fills, pijota summarises the older part of the conversation and continues.

| Setting | Default | Meaning |
|---|---|---|
| `compaction.enabled` | `true` | compact automatically |
| `compaction.reserve_tokens` | `16384` | headroom kept free for the next turn |
| `compaction.keep_recent_tokens` | `20000` | recent conversation left untouched |

`/compact` runs it now. `/branch-summary` summarises the current branch into context without
discarding it. `/context` shows the breakdown by category, `/usage` the tokens and cost so far.

The summary is written to the session as its own entry, so a resumed session sees the same context
the live one did.

## Crash safety

Writes are append-only and a torn final line is repaired when the file is next opened, so a crash
mid-turn costs at most the turn in flight. Ordinary appends are durable against a process crash;
compaction additionally syncs to disk.
