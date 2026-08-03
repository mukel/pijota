# Project files

Pijota reads resources from two places: `~/.pijota/` for every project, and `.pijota/` plus a
couple of Markdown files for the project in front of it.

| Path | Is |
|---|---|
| `AGENTS.md`, `CLAUDE.md` | instructions injected into context |
| `.pijota/CONTEXT.md` | the same, pijota-specific |
| `.pijota/skills/<name>/SKILL.md` | project skills |
| `.pijota/prompts/*.md` | project prompt templates |
| `.pijota/extensions/` | project extensions |
| `.pijota/mcp.json` | project MCP servers |

Anything project-local is only loaded for a folder you have trusted. Settings are not among them:
there is one `settings.json`, `~/.pijota/settings.json`, and a project cannot override it.

`.pi/extensions/` is read too, so an extension written for pi's layout is found without moving it.

## Trust

Project files can execute code, so pijota asks once per folder and remembers the answer in
`~/.pijota/trust.json`.

```bash
pj -a       # trust project-local resources for this run
pj -na      # ignore them for this run
```

`/trust` inside the TUI saves the decision for next time. It takes effect from the next turn.

Non-interactive modes never prompt. Without a saved decision they fall back to
`default_project_trust` in [settings](settings.md):

| Value | Non-interactive behaviour |
|---|---|
| `ask` (default) | ignore project resources |
| `always` | load them |
| `never` | ignore them, and stop asking interactively too |

Until a folder is trusted, its `AGENTS.md` and `CLAUDE.md` are not read, and the status line says
so.

## Context files

`AGENTS.md`, `CLAUDE.md` and `.pijota/CONTEXT.md` are injected into the system prompt. Disable
with `--no-context-files` or `"context_files": false`.

## Skills

A skill is a folder with a `SKILL.md`. YAML frontmatter names it; the body is the instruction
loaded on demand rather than pinned in context.

```markdown
---
name: release
description: Cut a release: version bump, changelog, tag.
---

Steps...
```

| Location | Scope |
|---|---|
| `~/.pijota/skills/` | every project |
| `.pijota/skills/` | that project, once trusted |
| `--skill <path>` | that run |

`/skills` lists them. `--no-skills` skips discovery. Without a `name`, the folder name is used;
without a `description`, the agent has no reason to pick it.

## Prompt templates

A `.md` file whose name becomes a slash command. Frontmatter is optional: `description` shows in
completion, `argument-hint` shows the expected arguments. With no description, the first line is
used.

```markdown
---
description: Review the working tree for security issues
argument-hint: [path]
---

Review $ARGUMENTS for injection, auth and secret-handling mistakes.
```

Saved as `~/.pijota/prompts/sec.md`, that is `/sec src/`.

| Location | Scope |
|---|---|
| `~/.pijota/prompts/` | every project |
| `.pijota/prompts/` | that project, once trusted |
| `--prompt-template <path>` | that run |

`--no-prompt-templates` skips discovery.

## MCP servers

`~/.pijota/mcp.json` globally, `.pijota/mcp.json` per project. Their tools join the session's tool
list and obey the same `--tool` / `--no-tool` filters.

```json
{
  "servers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
      "env": { "LOG_LEVEL": "warn" },
      "enabled": true
    }
  }
}
```

| Field | Meaning |
|---|---|
| `command` | executable to launch |
| `args` | its arguments |
| `env` | extra environment; `${VAR}` expands from the current environment |
| `enabled` | set `false` to keep the entry without starting it |

Servers connect in the background at startup, so the prompt appears immediately and their tools
arrive a moment later. A server that fails to start is reported with the tail of its stderr rather
than silently missing.
