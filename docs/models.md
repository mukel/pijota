# Models

53 models in the catalog, with context window and price. Any other id is sent verbatim.

## Choosing

```bash
pj -m claude-sonnet-4-5-20250929           # provider inferred
pj -m anthropic/claude-sonnet-4-5-20250929 # explicit
pj --provider deepseek                   # provider's default model
pj -m alias/fast                         # alias from settings
pj --list-models openai                  # what is available
```

| In the TUI | Does |
|---|---|
| `/model` | searchable picker |
| `Ctrl+L` | the same picker |
| `Ctrl+Shift+P` | next enabled model, no picker |
| `Shift+Tab` | cycle thinking level |

Only providers with a usable credential are offered.

## Aliases

Name a role, not a model.

```json
{
  "aliases": {
    "fast": "anthropic/claude-haiku-4-5-20251001",
    "deep": "openai/gpt-5.5",
    "print": "deepseek/deepseek-v4-flash"
  }
}
```

```bash
pj -m alias/fast -p "summarise this diff"
```

Targets are used verbatim, so an off-catalog id works. `pj -p` with no `--model` uses an alias
named `print`, if one exists.

## Narrowing the picker

```json
{ "enabled_models": ["anthropic/claude-sonnet-4-5-20250929", "deepseek/deepseek-v4-flash"] }
```

Empty means all.

## Thinking levels

`--thinking <level>`, `thinking_level` in settings, or `Shift+Tab`.

| Level | Budget |
|---|---|
| `off` | 0 |
| `minimal` | 1024 |
| `low` | 2048 |
| `medium` | 8192 |
| `high` | 16384 |
| `xhigh` | 32768 |
| `max` | 65536 |

The budget is what pijota asks for; a provider with no
[`thinkingFormat`](providers.md#thinkingformat) is told nothing and reasons when it likes.
`hide_thinking` only hides the blocks, it does not change what is requested or billed.

## Custom models

```bash
pj --provider local -m my-finetune-v3
```

Nothing to register. Without a catalog entry there is no context window or price, and `/usage`
reports the spend as unpriced.

## Sub-agents

`task` sub-agents inherit the session model unless `PIJOTA_SUBAGENT_MODEL` names another; same
`provider/model` and `alias/<name>` forms.
