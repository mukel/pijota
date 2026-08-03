# Models

Pijota ships a catalog of 53 models across the built-in providers, with context window and price
for each. A model outside the catalog still works: pass its id and pijota sends it verbatim.

## Choosing one

```bash
pj -m claude-sonnet-4-20250514          # id alone; the provider is inferred
pj -m anthropic/claude-sonnet-4-20250514 # explicit
pj --provider deepseek                   # provider only; uses its default model
pj -m alias/fast                         # an alias from settings
```

In the TUI, `/model` opens a searchable picker and `Ctrl+L` opens it directly. `Ctrl+Shift+P`
cycles forward through the enabled models without opening anything.

Only models whose provider has a usable credential are offered. `--list-models [filter]` prints
what is available and exits.

## Aliases

Name a role, not a model. Useful in scripts and for sub-agents.

```json
{
  "aliases": {
    "fast": "anthropic/claude-haiku-4-5",
    "deep": "openai/gpt-5.5",
    "print": "deepseek/deepseek-v4-flash"
  }
}
```

```bash
pj -m alias/fast -p "summarise this diff"
```

The `print` alias is special: `pj -p` uses it when no `--model` is given, so one-shot runs can be
cheaper than interactive ones without changing your default.

An alias target is used exactly as written, so it can name a model that is not in the catalog.

## Narrowing the picker

`enabled_models` is an allowlist. Empty means everything.

```json
{ "enabled_models": ["anthropic/claude-sonnet-4-20250514", "deepseek/deepseek-v4-flash"] }
```

## Thinking levels

`--thinking <level>`, `thinking_level` in settings, or `Shift+Tab` in the TUI, which cycles
through all seven.

| Level | Budget (tokens) |
|---|---|
| `off` | 0 |
| `minimal` | 1024 |
| `low` | 2048 |
| `medium` | 8192 |
| `high` | 16384 |
| `xhigh` | 32768 |
| `max` | 65536 |

The budget is what pijota asks for. Providers apply it differently, and some ignore it: an
OpenAI-compatible endpoint with no `thinkingFormat` is told nothing at all and reasons whenever it
likes. See [Providers](providers.md#thinkingformat).

`hide_thinking` hides the blocks without changing what is requested or billed.

## Custom models

Nothing to register. Any id reaches the provider as written:

```bash
pj --provider local -m my-finetune-v3
```

Without a catalog entry pijota cannot show a context window or price, and `/usage` reports the
spend as unpriced.

## Sub-agents

The `task` tool spawns sub-agents in separate processes. They inherit the session's model unless
`PIJOTA_SUBAGENT_MODEL` (or `-Dpijota.subagent.model=`) names another one, which accepts the same
`provider/model` and `alias/<name>` forms.
