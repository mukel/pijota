# Changelog

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
