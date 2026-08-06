# Project files

`~/.pijota/` applies everywhere; `.pijota/` and a couple of Markdown files apply to one project.

| Path | Is |
|---|---|
| `AGENTS.md` (or `CLAUDE.md`) | instructions injected into context |
| `.pijota/CONTEXT.md` | the same, pijota-specific |
| `.pijota/skills/<name>/SKILL.md` | project skills |
| `.pijota/agents/*.md` | project sub-agents |
| `.pijota/prompts/*.md` | project prompt templates |
| `.pijota/extensions/` | project extensions |
| `.pijota/mcp.json` | project MCP servers |

Project-local resources load only for a trusted folder, every one of them. Settings are not
among them: one `settings.json`, no project override.

## Trust

Project files can execute code, so pijota asks once per folder and records it in
`~/.pijota/trust.json`. Until then context files are not read and the status line says so.

```bash
pj -a       # trust for this run
pj -na      # ignore for this run
```

`/trust` saves the decision, effective next turn. Non-interactive modes never prompt and fall back
to `default_project_trust`:

| Value | Non-interactive |
|---|---|
| `ask` (default) | ignore project resources |
| `always` | load them |
| `never` | ignore them, and stop asking interactively too |

## Context files

`AGENTS.md` and `.pijota/CONTEXT.md` go into the system prompt, searching upward from the
working directory. Disable with `--no-context-files` or `"context_files": false`.

`AGENTS.md` is the cross-tool standard, so it is the one pijota reads. `CLAUDE.md` is a
fallback used only where a directory has no `AGENTS.md`: where a project keeps both, the
second holds overrides addressed to a different agent, and pijota has no business following
them. The choice is per directory, so a monorepo with `AGENTS.md` at the root and a
`CLAUDE.md` in one subproject gets both, each from the level that has it.

Symlinking `CLAUDE.md` to `AGENTS.md` is fine and reads once, for the tools that only look
for the other name. `.pijota/CONTEXT.md` is pijota's own layer and always loads last.

## Skills

A folder with a `SKILL.md`. The body loads on demand, not pinned in context. Without `name` the
folder name is used; without `description` the agent has no reason to pick it.

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

`/skills` lists them.

## Prompt templates

A `.md` file whose name becomes a slash command. Frontmatter is optional: `description` shows in
completion (else the first line), `argument-hint` shows the arguments. `$ARGUMENTS` expands.

```markdown
---
description: Review the working tree for security issues
argument-hint: [path]
---

Review $ARGUMENTS for injection, auth and secret-handling mistakes.
```

As `~/.pijota/prompts/sec.md`, that is `/sec src/`.

| Location | Scope |
|---|---|
| `~/.pijota/prompts/` | every project |
| `.pijota/prompts/` | that project, once trusted |
| `--prompt-template <path>` | that run |

## MCP servers

`~/.pijota/mcp.json` globally, `.pijota/mcp.json` per project. Their tools take the same
`--tools` / `--exclude-tools` filters as any other.

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

Servers connect in the background: the prompt appears at once and tools arrive a moment later. A
failure is reported with the tail of its stderr.
