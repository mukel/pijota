# Changelog

## v0.2.0

159 commits since v0.1.0: 69 fixes, 28 polish passes, 19 features. The theme of
the release is that the terminal now tells the truth -- about what it drew, what
it is doing, and what it can do.

### Features

- **Next-message suggestion** (`--suggest`, off by default). After a turn, the
  message you are most likely to type next appears as ghost text; Tab accepts it,
  Enter never does. It reuses the turn's cached prefix, so it costs a fraction of
  a fresh request, and it is answered by the session's own model. In the TUI and
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
