# Troubleshooting

## Nothing runs

**`No provider configured yet.`** No key and no stored credential. `pj login` for OAuth, or export
the provider's variable; [providers](providers.md#credentials) lists them all.

**`apiKey cannot be null or blank`** A provider was selected that has no key. Check which one
pijota picked -- `pj --list-models` shows only providers with a usable credential, and
`echo $PIJOTA_PROVIDER` inside a bash tool call reports the live one.

**`Unknown model alias 'x'`** `--model alias/x` with no `x` under `aliases` in
[settings](settings.md#model).

**`No prompt provided`** `-p` with nothing after it and nothing on stdin.

**`pijota: ignoring unknown option '-xyz'`** A single-dash flag pijota does not know. Extension
flags are `--name`, always two dashes.

## The model behaves oddly

**It cannot see `AGENTS.md`.** The folder is untrusted, and the status line says so. `/trust`, or
start with `-a`. See [project files](project-files.md#trust).

**It reasons when told not to.** `--thinking off` sends nothing at all on providers with no
[`thinkingFormat`](providers.md#thinkingformat), leaving the model to decide. Check with
`echo $PIJOTA_REASONING_LEVEL`, which reports what pijota asked for, not what the model did.

**It forgot the start of the conversation.** Compaction summarised it. `/context` shows the
breakdown; raise `compaction.keep_recent_tokens` to keep more.

**A tool it should have is missing.** `--readonly`, `--tools` or `--exclude-tools` filtered it, or its
extension is off. `/context` counts the tool schemas actually sent.

## The terminal

**Modifiers do nothing** -- Ctrl+P, Shift+Tab. Terminal-specific; see
[terminal setup](terminal-setup.md).

**`@` does not fuzzy-search files.** That picker is `fzf`. Without it installed, `@` still inserts
a reference, it just does not search.

**Colours look wrong or absent.** `NO_COLOR` disables them entirely. `--theme auto` guesses from
`COLORFGBG`, which some terminals do not set; name a theme instead.

**A theme change did nothing.** A user file wins over a bundled name, so a
`~/.pijota/themes/dark.json` shadows the shipped `dark`. `/theme` shows the path for file themes.

**The prompt looks wrong after a resize.** A rule drawn before the resize can survive it. New
output is unaffected.

## Sessions

**`-c` resumes the wrong thing.** Sessions are per-directory: `~/.pijota/sessions/<flattened-cwd>/`.
A different cwd is a different history.

**A session vanished.** `--no-session` was set, or `session_dir` points elsewhere.

## Frontends

**The web UI asks for a token.** Binding past loopback mints one; use the printed URL or QR
whole. See [frontends](frontends.md#web).

**Telegram exits at startup.** Both `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` are required.

**The editor shows no slash commands.** ACP registers them after the session opens.
`PIJOTA_ACP_WIRELOG=1` logs the traffic.

## Getting more out of it

`--verbose` writes debug logging to `~/.pijota/logs/pijota.log`; `PIJOTA_LOG_LEVEL=TRACE` goes
further. `pj --license` proves the binary carries its embedded resources, which is the usual
suspect when something works from source and not from the release build.
