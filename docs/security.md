# Security

**Pijota is not a sandbox.** It edits files and runs commands as you, in the directory you
launched it from. Everything below reduces the blast radius of a mistake; none of it contains a
determined one. For hard isolation use a container, a VM, a locked-down user, or a
[worktree](worktree.md).

## What is guarded

| Guard | Stops | Off with |
|---|---|---|
| project trust | a folder's own extensions, skills, sub-agents, prompts and MCP servers running unasked | `--approve` |
| `security` extension | `sudo`/`su`/`doas`, recursive `rm`, `dd`, pipe-to-shell | `/security`, `--no-security` |
| `filter-output` extension | secrets and tokens reaching the model | `/filter-output` |
| SSRF check | `webfetch` reaching localhost and private ranges | not disableable |
| tool policy | whole classes of action | `--readonly`, `--tool`, `--no-tool` |
| file permissions | other users reading your keys | not disableable |
| ACP permission prompts | tool calls your editor has not approved | the editor decides |
| browser launch confirm | `--browse` starting a browser silently | per prompt |

`--transcendence` (self-modification) is off by default and is the one flag that lets pijota
rewrite its own source.

## Trust

Project-local files can execute code, so pijota asks once per folder and records the answer in
`~/.pijota/trust.json`. Non-interactive runs never prompt and fall back to
`default_project_trust`, which defaults to **not** trusting. Details in
[project files](project-files.md#trust).

An untrusted folder still works: only its `.pijota/` resources and context files are skipped.

## Credentials

| File | Holds | Mode |
|---|---|---|
| `~/.pijota/` | the whole config directory | owner-only |
| `~/.pijota/settings.json` | API keys under `api_keys` | owner-only |
| `~/.pijota/auth.json` | OAuth access and refresh tokens | owner-only |

The two credential files are chmod'ed to the owner on write, and the directory itself is
created owner-only at startup, which is what covers everything else under it: `sessions/`
holds whole transcripts, `history` every prompt you have typed, and `logs/` the debug log.
A key in the environment is never written to disk.

All of that is POSIX, so it applies on Linux and macOS and is a **no-op on Windows**, where
the JDK cannot set POSIX modes and pijota sets no ACL of its own. A config directory in the
default profile still inherits that profile's user-only ACLs, but Windows is providing that,
not pijota, and it does not follow a `PIJOTA_HOME` pointed outside the profile. On Windows,
prefer WSL, or keep the home inside your user profile and check the ACLs yourself.
`pj auth print-api-key` exists so other tools can borrow a credential without you pasting it
around; it prints to stdout, so mind your shell history.

## Network

`webfetch` refuses localhost, link-local and private ranges, so a model cannot use it to probe
your network or reach a metadata endpoint. Reaching a local service is a `bash` job, which is an
explicit tool call rather than a URL fetch.

The web frontend authenticates with a 192-bit pairing token once it binds past loopback, but
speaks plain HTTP: fine on a home LAN, wrong on a shared network. See
[frontends](frontends.md#web).

## What is not guarded

- the `bash` tool runs what the model asks, minus the patterns above
- `write`, `edit` and `apply_patch` reach any path your user can write
- MCP servers are ordinary processes you configured; pijota does not sandbox them
- extensions you install run in-process with pijota's permissions

If that list is uncomfortable for a given task, `--readonly` is the honest answer: read, search,
fetch, ask, and nothing else.

## Reporting

Security issues: open a GitHub issue marked security, or contact the maintainer privately if the
report itself is sensitive.
