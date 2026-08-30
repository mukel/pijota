# Changelog

## v0.2.2

7 commits since v0.2.1: 4 fixes, 3 features. A small release with one theme: the messages
the agent sends now survive order-validating providers, and compaction no longer persists a
checkpoint it cannot stand behind.

### Features

- **The browser picks its own headless mode.** When no display is available the browser
  session goes headless on its own instead of failing to launch.
- **Extensions can declare auxiliary boolean flags.** An extension's `--flag` no longer needs
  a value; it is a switch.
- **GLM 5.3 and 5.3 Flash joined the catalog** on Fireworks, both with a 1M context window;
  Flash is multimodal at a tenth of the price.

### Fixes worth naming

- **A dangling `tool_choice` no longer breaks tool-less requests.** Summarization and other
  side requests sent a leftover `tool_choice` that strict validators reject.
- **Compaction refuses a length-stopped summary** instead of persisting a truncated one as
  the session checkpoint; the finish reason is plumbed through `StreamResult`.
- **Injected messages wait for an append-safe point.** A custom message arriving mid-turn or
  mid-compaction used to land between a tool call and its result, which order-validating
  providers reject on replay; it now queues and flushes after tool results, at turn end, at
  compaction end, or when the agent settles.
- **Overlapping history rewrites are refused.** `/compact` and `/undo` can no longer rewrite
  the same history at the same time.
- **Job completion notices are delivered, not queued** behind whatever the agent is doing,
  and the web composer's multiline layout no longer wastes its rows.

## v0.2.1

125 commits since v0.2.0: 50 fixes, 10 features. The theme of the release is that the
session leaves the terminal: your live conversation can be driven from a phone or a browser,
and the numbers the agent works with -- context windows, retries, limits -- are the real ones
now, not the ones the catalog guessed.

### Features

- **`/remote-control`** puts the live session behind a channel of your choosing instead of
  starting a new one: a Tailscale URL for your devices only (foreground `tailscale serve`
  child, dies with pijota), a `cloudflared` quick tunnel for a public `trycloudflare.com`
  URL, the Telegram bot, or a LAN web bind. The web and Telegram channels share one startup
  path, and each tunnel carries its own bind host, banner and diagnostics.
- **The catalog tells the truth about context.** Pijota reads the context length a provider
  reports, resolves a local server's window live instead of from a stale cache, and a bare
  `--provider` means that provider's default model. GPT-5.6 Sol, Terra and Luna and Kimi K3
  256K joined the catalog, and a one-million-token window is spelled `1M` in the status bar
  too.
- **Compaction at an absolute token ceiling.** Compaction now fires against a ceiling you can
  set, not only just under the context limit, and it stops retrying an overflow that
  compaction cannot fix. The context anchor invalidates itself when the history it measured
  is gone, so `/undo` and `/compact` can no longer leave a stale measurement behind.
- **Retries are visible.** When a request is retried, the agent says so instead of silently
  waiting out the backoff.
- **`editor` and `proxy` settings**, explicit before ambient: the `editor` key names the
  command `Ctrl+G` opens, and `proxy` sits between `-Dpijota.proxy` / `PIJOTA_PROXY` and
  `HTTPS_PROXY` in precedence.
- **Usage errors, a help that fits, a resize that repaints.** Bad flags fail with the flag
  named, `--help` fits an 80-column terminal, and narrowing the window repaints the rules
  instead of threading blank rows through the transcript.
- **Security verdicts are logged.** Every decision on a dangerous command goes to the log,
  and `/export` redacts credentials on the way out.

### Fixes worth naming

- **One bad byte no longer costs a whole conversation.** A corrupt session entry is skipped
  instead of failing the open, and session writes fsync what nobody can reproduce.
- **Every user message is persisted, not only the typed one.** A piped prompt used to reach
  the agent loop without ever being written to disk, so a resumed session had the model
  talking to itself.
- **Esc stops the turn, not the program.** The Ctrl+C hint that read as the way to stop what
  is running is gone -- and **Ctrl+Z stops the supervisor too**, not just the worker it
  started. A supervised worker can no longer outlive the supervisor that spawned it.
- **Sub-agents got an absolute classpath**, so a `task` spawned from a relative launch no
  longer fails to find its own tools.
- **The status bar stopped twitching** as the token count filled, and a dead terminal leaves
  quietly instead of letting Shift+Tab rewrite `settings.json` on the way out.
- **The web UI streams events over POST**, so a buffering tunnel can no longer stall the
  chat, and the 128KB flush pad that papered over it is gone.
- **A goal loop stopped queueing the same re-prompt two dozen times**, and `--export` takes
  a session id and names the session it exported.

### Polish

The TUI was decomposed into named pieces -- keys as data, one class for "what is running
right now", the input loop reading as a loop, every agent event answered in one line -- and
the confirm keys live on the question, not in the warning slot. Two perf passes: pijota
stopped resolving a provider's name on every launch, and the web SSE buffer is scanned once
instead of once per chunk.

### Removed

- **Plan mode is now an example extension**, off unless asked (`--plan`). It stopped
  throwing away the prompt cache on entry and gives back the tools it borrowed on exit, but
  it is a pattern to copy, not a feature to ship.

## v0.2.0

159 commits since v0.1.0: 69 fixes, 28 polish passes, 19 features. The theme of
the release is that the terminal now tells the truth -- about what it drew, what
it is doing, and what it can do.

### Features

- **Next-message suggestion** (`--suggest`, off by default). After a turn, the
  message you are most likely to type next appears as ghost text; Tab accepts it,
  Enter never does. It reuses the turn's cached prefix, so it costs a fraction of
  a fresh request, and it is answered by the session's own model -- though never
  at the session's reasoning effort, since thinking is the entire delay before a
  suggestion appears and none of it improves a five-word sentence. In the TUI and
  in the web UI, where a tap accepts it instead.
- **`--mode json` streams events** as NDJSON while the run happens, instead of one
  blob at the end, so a wrapper can follow along.
- **`pijota auth`** prints a credential for another tool to borrow.
- **Kitty keyboard protocol**, so modifiers stop being guesswork, and Ctrl+Alt is
  a keyspace extensions can bind into.
- **`/jobs`, `/kill`, `/bg`** and Ctrl+B backgrounding for long-running commands.
- **`--license`** prints pijota's license and the attribution notices for everything embedded in the
  binary. The binary is what most people have, and the repository is not part of it.

### Fixes worth naming

- **A resize damaged the transcript.** Any row that filled the last column was
  flagged wrapped by the terminal, so narrowing the window by a single column
  split the prompt rules, shifted everything below them, and left a doubled rule
  and blank rows threaded through output already on screen -- permanently, since
  new output appends below. Rules now stop one column short. (Print 100 `─` in a
  100-column tmux pane and resize to 99: it comes back as 99 + 1. Print 99 and it
  survives.)
- **The prompt lied about what Enter would do.** With the completion popup open,
  arrowing to `/model` left the ghost reading `/help`: the popup and the prompt
  are two views of one pending choice and nothing kept them in step.
- **Narrow terminals collapsed the pickers.** At 60 columns `/model` sized its
  title column to the longest id and rendered every row as `[deepseek]  …`, and
  the selector footer was clipped mid-word, losing "Esc cancel" -- the one hint
  that says how to get out. Everything that has to shrink now gives way in
  priority order and never cuts through a value.
- **`--mode rpc` was unreachable.** JSON-RPC arrives on stdin by definition, and
  the branch that claims redirected stdin ran first, so the request lines were
  concatenated into a prompt and answered by the model in prose. The mode was
  reachable only from a terminal, where nothing can speak to it.
- **`--api-key` did nothing.** It was parsed, resolved, and dropped; runs used the
  stored credential instead, or failed as "apiKey cannot be null or blank" while
  holding the key they had been handed. `--fork` and `--verbose` were dead the
  same way.
- **`pijota -p` sessions had no question in them.** Print mode prompted the agent
  loop directly, past the one place a user turn is written to disk, so
  `--continue`, `--fork`, `/load` and `--export` all resumed a conversation where
  the model appeared to be talking to itself.
- **Terminal widths were wrong for every mark pijota draws.** The tool glyphs,
  the user marker, the interrupt mark and the caret were all measured two cells
  wide and drawn in one, so headers wrapped early and columns drifted. Emoji were
  wrong in the other direction: a variation selector counted as a column, and a
  joined emoji as the sum of its parts.
- **`!zsh` hung the frontend indefinitely.** A captured subprocess was given a
  pipe for stdin that nobody wrote to and nobody closed, so anything reading
  stdin blocked forever. That applied to every captured child, including the
  model's own bash calls.
- **`--browse` extracted nothing in the native binary.** The injected scripts
  were loaded from the classpath and never registered for the native image, and
  the loader answers "" for a missing resource.
- **Every extension keybinding was dead.** Both shipped shortcuts bound Ctrl+Alt,
  which no decoder path could produce, while `/help` listed them.
- **An unreachable host looked like a hang.** Three HTTP clients set no connect
  timeout, so the OS decided; `/refresh` and `login` could sit for minutes.

### Polish

The interface now speaks one language. Blocks open with a mark that means
something and close when they end; red means something failed, amber means not
right now, and an empty list is neither; a model is named `provider/model` with
its real id everywhere, so it can be pasted back into `--model`; panels share one
header grammar; only one overlay is open at a time; and the effort chip names
every level, including off.

`--help` fits an 80-column terminal, its columns line up, and every flag it
advertises does something.

### Removed

- The four Catppuccin themes, which were vendored incompletely: all of them left
  the main text slot unset, so the palette coloured everything except the thing
  you read. `dark`, `light`, `gruvbox-dark` and `gruvbox-light` remain.

## v0.1.0

First release.

Pijota is a coding agent written in Java, shipped as a single native binary. It reads and edits code,
runs commands, searches files, browses the web, uses MCP tools, delegates to sub-agents, and remembers
work across sessions.

### Highlights

- **Single native binary.** GraalVM native-image; ~10ms startup, ~25MB resident.
- **Any model.** OpenAI, Anthropic, Gemini, OpenAI-compatible endpoints, local servers, custom
  providers, aliases, OAuth and API keys. All three provider wire formats are smoke-tested against the
  native binary (`make native-smoke`).
- **Append-only TUI.** No alternate-screen takeover: output stays in normal scrollback, so copy,
  search, scroll and panes keep working. Streaming markdown, diffs, fuzzy pickers, themes, `$EDITOR`
  integration.
- **Real tools.** read, write, edit, apply_patch, glob, grep, ls, bash, webfetch, websearch, question,
  task (parallel sub-agents in separate processes, optionally in isolated git worktrees).
- **Durable sessions.** Append-only JSONL entry tree: resume, fork, branch, compact, name, import,
  export. A crash mid-turn is repaired on the next open rather than corrupting the file.
- **Five frontends** on one agent core: TUI, print mode, JSON-RPC, ACP (Zed and other editors), a
  local web UI with phone pairing, and a Telegram bot.
- **Extensions and MCP.** Add tools, slash commands, keybindings, prompt context, status lines and
  safety hooks; import tools from MCP servers.
- **Self-modification** (`--transcendence`, off by default): edit its own source, build, test, respawn,
  and recover from a failed self-update.

### Requirements

JDK 21 for the JBang install path; the native binary needs no JDK. Linux and macOS are first-class;
on Windows use WSL.

### Install

```bash
jbang app install --name pj org.pijota:pijota:RELEASE          # JVM
jbang app install --native --name pj org.pijota:pijota:RELEASE  # native image
```

### Known limitations

- Not a sandbox: pijota runs with your user's permissions. Use a container, VM or disposable worktree
  when you need hard isolation.
- Compaction triggers near the context limit; there is no manual compaction of a small conversation.
- Windows support is best-effort and expects Git Bash or a configured `shell_path`.
