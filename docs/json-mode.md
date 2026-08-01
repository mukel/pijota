# JSON event stream

```bash
pijota --mode json -p "fix the failing test"
```

Writes one JSON object per line to stdout as the run happens, so a wrapper UI,
a CI annotator, or a progress bar can follow along instead of learning
everything at once when the process exits. Every line has a `type`. The last
line is always `result`.

Diagnostics go to stderr, so stdout is always parseable.

## Reading it

```bash
pijota --mode json -p "..." | while read -r line; do
  jq -r 'select(.type == "tool_start") | "→ \(.tool)"' <<<"$line"
done
```

Only want the outcome? The last line is the whole summary:

```bash
pijota --mode json -p "..." | tail -1 | jq '.usage.outputTokens'
```

## Events

| `type`            | Fields                                                            | When                                        |
| ----------------- | ----------------------------------------------------------------- | ------------------------------------------- |
| `turn_start`      | `turn`                                                            | A request to the model begins               |
| `turn_end`        | `turn`                                                            | Its tool calls have all resolved            |
| `stream_start`    | `messageType`                                                     | The model starts producing a message        |
| `delta`           | `text`                                                            | A chunk of that message                     |
| `stream_end`      | `messageType`                                                     | The message is complete                     |
| `assistant`       | `content`, `thinking`, `usage`                                    | The settled message, with its token cost    |
| `tool_start`      | `tool`, `toolCallId`, `arguments`                                 | A tool call begins                          |
| `tool_output`     | `toolCallId`, `delta`                                             | Live output from a running shell command    |
| `tool_result`     | `tool`, `toolCallId`, `isError`, `content`                        | The call finished                           |
| `compaction`      | `reason`, `oldMessageCount`, `estimatedChars`, `estimatedTokens`, `summary` | Context was summarized to make room |
| `history_changed` | `reason`                                                          | History was replaced (new, load, undo, fork) |
| `custom`          | `customType`, `content`                                           | An extension emitted a message              |
| `error`           | `message`                                                         | The run failed                              |
| `result`          | `provider`, `model`, `messages`, `usage`                          | Always last                                 |

`assistant` is emitted once per model message, when it settles: while streaming,
the message repeats with cumulative content, and the `delta` lines already carry
that. Render from the deltas, then reconcile with `assistant`.

Pair a `tool_start` with its `tool_result` by `toolCallId`; tool calls in one
batch run concurrently, so the results do not necessarily arrive in call order.

## Usage totals

`usage` on an `assistant` line is that request's cost. The `usage` on `result`
is the sum over the whole run, which is what you want for budgeting:

```json
{"type":"result","provider":"deepseek","model":"deepseek-v4-flash",
 "messages":[{"role":"assistant","content":"It prints `hi`."}],
 "usage":{"inputTokens":861,"outputTokens":184,"cacheReadTokens":2688,
          "cacheWriteTokens":0,"reasoningTokens":37}}
```

## Other modes

- `--mode text` (default) streams plain text, nothing else.
- `--mode rpc` speaks the Agent Client Protocol over stdio, for editors. Use
  that rather than this if you are building an editor integration; it is
  bidirectional.
