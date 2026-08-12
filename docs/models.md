# Models

60 models in the catalog, with context window and price. Any other id is sent verbatim.

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

## Discovery

The bundled catalog covers the hosted providers. It cannot cover a local endpoint or a company
gateway, so pijota asks each configured provider what it serves and remembers the answer in
`~/.pijota/models-cache.json`.

| When | What happens |
|---|---|
| `pj login` / `pj logout`, `/login` / `/logout` | asks now: the reachable set just changed |
| every later start | reads the remembered answer, no network |
| `/refresh` in the TUI, `pj --refresh-models` outside it | asks again, whenever you want |

A provider needing no key never triggers a login, so ask once after adding one; it sticks from
then on. `--refresh-models` takes the same optional filter as `--list-models` and prints the
result, so it answers "what can I run now" on its own:

```bash
pj --refresh-models            # ask everyone, then list
pj --refresh-models mylocal    # ask everyone, then list what matches
```

A provider that cannot be reached keeps the models it reported last time, so a refresh with the
laptop offline costs nothing. `PIJOTA_OFFLINE` stops the automatic asking; asking explicitly
still works.

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
