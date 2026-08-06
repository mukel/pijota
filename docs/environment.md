# Environment

Every `PIJOTA_*` tuning variable has a system-property twin, which wins when both are set.

## Read by pijota

| Variable | Property | Does |
|---|---|---|
| `PIJOTA_HOME` | `pijota.home` | move `~/.pijota` |
| `PIJOTA_PROXY` | `pijota.proxy` | HTTP proxy for outbound requests |
| `PIJOTA_OFFLINE` | `pijota.offline` | never discover models on its own; `/refresh` still works |
| `PIJOTA_CONNECT_TIMEOUT` | `pijota.connect-timeout` | connect timeout in seconds (default 10) |
| `PIJOTA_LLM_CONNECT_TIMEOUT` | `pijota.llm.connect-timeout` | connect timeout for provider calls |
| `PIJOTA_LLM_REQUEST_TIMEOUT` | `pijota.llm.request-timeout` | whole-request timeout for provider calls |
| `PIJOTA_SUBAGENT_MODEL` | `pijota.subagent.model` | model for `task` sub-agents |
| `PIJOTA_BROWSER` | `pijota.browse.engine` | `chrome`, `firefox`, `safari` |
| `PIJOTA_BROWSER_BIN` | `pijota.browse.bin` | path to the browser binary |
| `PIJOTA_BROWSER_IDLE_SECONDS` | `pijota.browser.idleSeconds` | how long a page must be quiet before it is read |
| `PIJOTA_BROWSER_FAST_SETTLE` | `pijota.browser.fastSettle` | collapse the settle/consent waits; the test suite sets it |
| `PIJOTA_GECKODRIVER` | - | path to geckodriver, for `--browse=firefox` |
| `PIJOTA_SAFARIDRIVER` | - | path to safaridriver, for `--browse=safari` |
| `PIJOTA_WEB_TIMEOUT` | `pijota.web.timeout` | `webfetch` request timeout |
| `PIJOTA_WEB_CONNECT_TIMEOUT` | `pijota.web.connect-timeout` | `webfetch` connect timeout |
| `PIJOTA_TELEGRAM_TIMEOUT` | `pijota.telegram.timeout` | Telegram long-poll timeout |
| `PIJOTA_TELEGRAM_CONNECT_TIMEOUT` | `pijota.telegram.connect-timeout` | Telegram connect timeout |
| `PIJOTA_LOG_LEVEL` | - | `DEBUG` or `TRACE`; `--verbose` sets `DEBUG` |
| `PIJOTA_STARTUP_TIMING` | - | print startup phase timings |
| `PIJOTA_ACP_WIRELOG` | - | log the ACP wire protocol |

Terminal and shell:

| Variable | Does |
|---|---|
| `NO_COLOR` | disable colour entirely |
| `COLORFGBG` | tells `--theme auto` whether the terminal is light or dark |
| `EDITOR` | editor for `Ctrl+G`; defaults to `vi`, or Notepad on Windows |
| `SHELL` | shell for the bash tool when `shell_path` is unset |

Frontends and tools:

| Variable | Does |
|---|---|
| `TELEGRAM_BOT_TOKEN` | required by `pj telegram` |
| `TELEGRAM_CHAT_ID` | restricts the bot to one chat |
| `EXA_API_KEY`, `PARALLEL_API_KEY` | `websearch` backends |

## Set by pijota

Every bash command receives the session state, resolved per command: switching model or thinking
level affects the next one without a restart.

| Variable | Is |
|---|---|
| `PIJOTA_CODING_AGENT` | `true`; lets a child detect it is running inside pijota |
| `PIJOTA_PROVIDER` | current provider id |
| `PIJOTA_MODEL` | current model id |
| `PIJOTA_REASONING_LEVEL` | `off`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max` |
| `PIJOTA_SESSION_FILE` | path to the session JSONL; unset with `--no-session` |
| `PIJOTA_SESSION_ID` | session UUID; unset with `--no-session` |

```bash
printf '%s/%s (%s)\n' "$PIJOTA_PROVIDER" "$PIJOTA_MODEL" "$PIJOTA_REASONING_LEVEL"
```

Ask the shell, not the system prompt, for the running model.

## Provider keys

One per provider, listed in [Providers](providers.md#built-in). A key in the environment needs no
`pj login`. Precedence: `--api-key`, environment, `~/.pijota/auth.json`.

Not every provider ships a bundled model list; `pj --list-models <provider>` says so and names the
command. `pj --refresh-models <provider>` asks it what it serves and remembers the answer.
