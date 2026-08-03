# RPC mode

```bash
pijota --mode rpc
```

JSON-RPC 2.0 over stdin/stdout, one object per line. For embedding pijota in another program that
wants request/response rather than a stream; for following a run as it happens, use the
[JSON event stream](json-mode.md).

## Requests

| Method | Params | Returns |
|---|---|---|
| `prompt` | `{"prompt": "<text>"}` | the assistant's text, as a JSON string |
| `ping` | - | `"pong"` |
| `shutdown` | - | `null`, then the process exits |

```json
{"jsonrpc":"2.0","id":1,"method":"prompt","params":{"prompt":"what does pom.xml configure?"}}
```

`id` may be a number or a string, and comes back as it was sent. Anything else is a
`-32601 Method not found`; a line that is not JSON is skipped.

## Responses and notifications

A `prompt` produces three lines: a start notification, an end notification, then the response.

```json
{"jsonrpc":"2.0","method":"prompt_start","params":{"id":1}}
{"jsonrpc":"2.0","method":"prompt_end","params":{"id":1}}
{"jsonrpc":"2.0","id":1,"result":"It configures the Maven build..."}
```

| Notification | When |
|---|---|
| `prompt_start` | the turn began |
| `prompt_end` | it finished |
| `prompt_error` | it failed; carries `message` |

The result is the assistant's text with tool calls and thinking removed. Errors use the JSON-RPC
`error` object: `-32601` for an unknown method, `-32603` internally.

## Example

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"ping"}' \
  '{"jsonrpc":"2.0","id":2,"method":"prompt","params":{"prompt":"say ok"}}' \
  '{"jsonrpc":"2.0","id":3,"method":"shutdown"}' | pijota --mode rpc
```

Session, model, tool and extension flags all apply, so `--no-session`, `-m`, `-t` and the rest
work the same as anywhere else.
