<div align="center">

# Pijota

### A local-first AI coding agent for people who live in terminals.

<p align="center">
  <a href="https://openjdk.org/projects/jdk/21/"><img src="https://img.shields.io/badge/Java-21+-007396?logo=openjdk&logoColor=white" alt="Java 21"></a>
  <a href="https://www.graalvm.org/latest/reference-manual/native-image/"><img src="https://img.shields.io/badge/GraalVM-Native_Image-F29111?labelColor=00758F" alt="GraalVM Native Image"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-green" alt="License"></a>
</p>

**A terminal-native coding agent with real tools, durable sessions, many models, and multiple frontends.**

The command is **`pj`**.

</div>

---

## What Is Pijota?

Pijota is an AI coding agent written in Java. It can read and edit code, run commands, search files, browse the web, use MCP tools, delegate to sub-agents, and remember work across sessions.

It is heavily inspired by [Pi](https://pi.dev), **unashamedly so**: small sharp tools, agentic loops, extension-driven behavior, and a codebase meant to be hacked by its users. Pijota reimagines that shape on the JVM with a simpler append-only terminal UI that serves most user needs, plus a native-image path, browser automation, ACP editor integration, web/Telegram frontends, and self-modification as an opt-in capability.

> [!NOTE]
> Pijota is local software with powerful tools. When you give it shell or file access, it acts with your user permissions.
> It is not a sandbox; use containers, VMs, locked-down users, or disposable worktrees when you need hard isolation.

---

## Highlights

| Feature | Why It Matters |
|---|---|
| **Append-only TUI** | No alt-screen takeover. Output stays in normal terminal scrollback, so copy/search/scroll/panes keep working. |
| **Real coding tools** | Read, write, edit, patch, grep, glob, run bash, fetch URLs, ask questions, and delegate to sub-agents. |
| **Bring your own model** | OpenAI, Anthropic, Gemini, OpenAI-compatible APIs, local endpoints, custom providers, aliases, OAuth, and API keys. |
| **Durable memory** | Continue, resume, fork, clone, compact, name, and export conversations. |
| **Multiple frontends** | Use the terminal, print mode, JSON-RPC, ACP in editors, the local web UI, or Telegram. |
| **Extensions everywhere** | Add tools, slash commands, keybindings, prompt context, status lines, and safety hooks. |
| **Browser and MCP** | Browse rendered pages, extract readable Markdown, and import tools from MCP servers. |
| **Self-modifying mode** | Opt in to let Pijota edit, build, test, respawn, and recover from failed self-updates. |

---

## Quick Start

Requires **JDK 21**. The simplest install path is [JBang](https://www.jbang.dev/), which installs the published Maven artifact as `pj`.

Linux and macOS are first-class targets. On Windows, use WSL for the best experience; native Windows is best-effort and
expects Git Bash or a configured `shell_path` for shell tools.

```bash
jbang app install --name pj org.pijota:pijota:RELEASE
pj --help
pj login anthropic
pj -p "explain this repo"
```

Maven coordinates: `org.pijota:pijota`.

To test quickly with an API key, start with one of the open-model or coding-friendly providers:

| Provider | Environment Variable |
|---|---|
| Kimi For Coding | `KIMI_API_KEY` |
| Moonshot AI / Kimi | `MOONSHOT_API_KEY` |
| DeepSeek | `DEEPSEEK_API_KEY` |
| Fireworks AI | `FIREWORKS_API_KEY` |
| Together AI | `TOGETHER_API_KEY` |
| OpenRouter | `OPENROUTER_API_KEY` |
| Hugging Face | `HF_TOKEN` |
| NVIDIA NIM | `NVIDIA_API_KEY` |
| Groq | `GROQ_API_KEY` |
| Mistral | `MISTRAL_API_KEY` |
| Cerebras | `CEREBRAS_API_KEY` |
| Z.AI | `ZAI_API_KEY` |
| MiniMax | `MINIMAX_API_KEY` |
| Anthropic | `ANTHROPIC_API_KEY` |
| OpenAI | `OPENAI_API_KEY` |
| Google AI / Gemini | `GEMINI_API_KEY` or `GOOGLE_API_KEY` |

```bash
export KIMI_API_KEY="..."
pj -m kimi-coding/k3 -p "explain this repo"
```

To install the native-image build with JBang:

```bash
jbang app install --native --name pj org.pijota:pijota:RELEASE
```

To refresh an existing JBang install to the latest published release:

```bash
jbang app install --force --fresh --name pj org.pijota:pijota:RELEASE
jbang app install --force --fresh --native --name pj org.pijota:pijota:RELEASE
```

For source builds, see [Build From Source](#build-from-source).

---

## Everyday Usage

```bash
pj                                        # interactive TUI
pj -p "summarize the diff"                # print mode
cat error.log | pj -p "what broke?"       # stdin becomes the prompt

pj -m anthropic/claude-sonnet-4-5-20250929
pj --thinking high

pj -c                                     # continue last session
pj -r                                     # session picker
pj -s <session-id-or-path>                 # load by id/path
pj --export transcript.md                 # export the current session

pj --readonly                             # read-only tools only
pj --tool read,grep,glob                  # exact tool allowlist
pj --no-tool bash,write,edit              # exact tool denylist

pj web                                    # local web UI
pj telegram                               # Telegram bot frontend
pj acp                                    # editor integration over stdio
pj worktree                               # temporary pooled git worktree
```

Inside the TUI, start with `/help`.

| Command | Purpose |
|---|---|
| `/login` | Configure provider credentials. |
| `/model` | Switch models. |
| `/settings` | Adjust runtime settings. |
| `/status` | Show current model, session, cwd, usage, and busy state. |
| `/name` | Name the current session. |
| `/new` | Start a new conversation. |
| `/stop` | Abort the current turn. |
| `/compact` | Summarize older context. |
| `/usage` | Show tokens, cost, and context. |
| `/dequeue` | Restore a queued follow-up to the editor. |
| `/sessions` | Resume a session. |
| `/tree` | View branches. |
| `/branch-summary` | Summarize the current branch into context. |
| `/theme` | Switch themes. |
| `/reload` | Refresh model metadata. |
| `/remote-control` | Control the live session from a phone/browser or Telegram. |

Longer user guides live in [`docs/`](docs/). Start with [`docs/worktree.md`](docs/worktree.md) for isolated git worktrees.

---

## Feature Tour

### Terminal First

The TUI is designed to feel like a good Unix program. It streams Markdown into normal scrollback, shows readable diffs, supports fuzzy pickers, integrates `$EDITOR`, and keeps status information visible without taking over your terminal.

### Tools That Work

Pijota gives the model a compact tool belt: `read`, `write`, `edit`, `apply_patch`, `glob`, `grep`, `ls`, `bash`, `webfetch`, `websearch`, `question`, `task`, and optional extension tools.

Tool access follows one rule: **deny wins**. A tool that appears in both `--tool` and `--no-tool` is disabled.

```bash
pj --readonly                 # read, search, fetch, ask; no file edits or shell
pj --no-tools                 # no model-callable tools
pj --tool read,grep,glob      # only these tools
pj --no-tool bash,write,edit  # all default tools except these
```

`--tools` and `--exclude-tools` are accepted for compatibility, but the singular `--tool` and `--no-tool` forms are the primary allow/deny interface.

Tools move through three states: registered, available by policy, then active for the next model request. Extensions may register tools dynamically; the same policy still applies.

Shell commands launched by Pijota receive session context:

| Variable | Value |
|---|---|
| `PIJOTA_PROVIDER` | Current model provider. |
| `PIJOTA_MODEL` | Current model ID. |
| `PIJOTA_REASONING_LEVEL` | Current reasoning level. |
| `PIJOTA_SESSION_FILE` | Current session file, when persisted. |
| `PIJOTA_SESSION_ID` | Current session ID, when persisted. |
| `PIJOTA_CODING_AGENT` | Always `true` inside Pijota-launched commands. |

### Sessions And Branches

Sessions can be resumed, forked, cloned, compacted, named, imported, and exported. Conversation trees stay available, so experiments do not have to overwrite each other.

### Models And Frontends

Use hosted models, local models, OpenAI-compatible endpoints, or custom providers. Pijota supports model aliases, reasoning levels, model pickers, OAuth where available, provider-specific API keys, and `--list-models` discovery.

The same agent core powers the TUI, print mode, JSON-RPC, ACP editor clients, web UI, and Telegram bot.

| Frontend | Command | Notes |
|---|---|---|
| TUI | `pj` | Append-only terminal UI with Markdown, pickers, themes, hotkeys, and file references. |
| Print | `pj -p "..."` | Single-shot mode for scripts and pipes. |
| RPC | `pj --mode rpc` | JSON-RPC over stdin/stdout. |
| ACP | `pj acp` | Editor integration over stdio. |
| Web | `pj web [host:port]` | Local browser UI with streaming updates. |
| Telegram | `pj telegram` | Bot frontend for a configured chat. |

### Browser, MCP, And Skills

Enable browser automation with `--browse`. Connect MCP servers for extra tools. Add reusable workflows as skills, prompt templates, and project context files unless disabled.

### Extensions

Extensions add capabilities to Pijota. Some add tools the model can call; others add slash commands, prompt context, keybindings, status lines, renderers, or safety policies.

Built-in extensions cover browsing, background jobs, tmux, todos, token speed, skills, prompt templates, context files, and transcendence. User extensions are loaded from `~/.pijota/extensions`, explicit `--extension <path>` arguments, and trusted project extension directories.

Disable all extensions with `--no-extensions`, or only shipped extensions with `--no-builtin-extensions`. Individual extensions use `--name` / `--no-name`, for example `--todo` or `--no-todo`.

Persist extension choices in `~/.pijota/settings.json`:

```jsonc
{
  "extensions": {
    "browse": "firefox",
    "jobs": true,
    "todo": true,
    "transcendence": false
  }
}
```

### Transcendence

`--transcendence` enables self-modification. Pijota can edit its own Java source, build, test, respawn, and recover if a self-update fails.

```bash
pj --transcendence
/transcend add a tiny /doctor command that checks provider configuration
```

---

## Configuration

Settings live in `~/.pijota/settings.json`. API keys can come from settings, OAuth token stores, or the environment variables listed in Quick Start.

Useful paths:

| Path | Purpose |
|---|---|
| `~/.pijota/settings.json` | Runtime settings. |
| `~/.pijota/sessions/` | Session JSONL files. |
| `~/.pijota/logs/` | Runtime logs. |
| `~/.pijota/mcp.json` | MCP server config. |
| `~/.pijota/extensions/` | User extensions. |
| `~/.pijota/skills/` | User skills. |
| `~/.pijota/keybindings.json` | TUI keybindings. |
| `~/.pijota/tool-output/` | Saved full tool outputs. |
| `.pijota/extensions/` | Trusted project extensions. |
| `.pijota/skills/` | Project skills. |

---

## Build From Source

The repo uses `make` as a small wrapper around Maven for shorter, repeatable commands.

```bash
make check
make jar                 # Maven thin jar; use JBang or a Maven classpath to run it
make fat
make native              # writes target/pj
make native-static       # fully static target/pj; run make setup-musl first
```

`make fat` builds a standalone JVM jar for manual local use. The Maven Central artifact is the normal thin jar; JBang
resolves its dependencies from Maven.

Run from compiled classes:

```bash
make run ARGS="--help"
```

---

## Security Model

Pijota is not a sandbox. It can edit files and run commands in the environment where you launch it.

Safety features include tool scoping, ACP permission prompts, project-local extension trust, SSRF checks, opt-in browser launch confirmation, owner-only settings files, and opt-in self-modification. They reduce risk; they do not replace process isolation.

If you need hard isolation, run Pijota inside a container, VM, locked-down user account, or disposable worktree.

---

## License

[Apache 2.0][license]

[license]: LICENSE
