# Security Policy

## Supported versions

The latest release. Pijota is a single binary with no server component, so upgrading is the fix
for anything reported here.

## Reporting a vulnerability

Use GitHub's [private vulnerability reporting](https://github.com/mukel/pijota/security/advisories/new)
for anything exploitable. It stays private until there is a fix.

For issues that are not sensitive to disclose, a normal GitHub issue marked `security` is fine
and gets the same attention.

Please include what you were running (`pj --version`), what you expected, and what happened.
A reproduction beats a description.

## What is in scope

Pijota runs local code with your permissions by design, so the boundaries it does claim are the
ones worth testing:

- **Project trust.** A folder's `.pijota/` (extensions, skills, sub-agents, prompts, MCP
  servers) and its `AGENTS.md` must not load until the folder is trusted. Anything that gets a
  cloned repository's instructions or commands to run without that is a vulnerability.
- **Credentials.** `settings.json` and `auth.json` hold API keys and OAuth tokens. Anything that
  puts a key on screen, in a transcript, in a log, or in a request to a model is a vulnerability.
- **SSRF.** `webfetch` refuses internal addresses, on every redirect hop as well as the first.
- **The config directory.** `~/.pijota/` is created owner-only on POSIX systems, which is what
  keeps transcripts, prompt history and logs private on a shared machine.

## What is not

Pijota is **not a sandbox**, and does not claim to be. With the shell tool it runs commands as
you; with the file tools it edits what you can edit. A prompt that persuades the model to do
something destructive is the model doing what it was asked, not a boundary being broken. For a
real boundary, run pijota in a container, a VM, a locked-down user account, or a disposable
[worktree](docs/worktree.md).

The `security` and `filter-output` extensions refuse obviously dangerous commands and redact
known secret shapes. They are backstops against accidents, not defences against an adversary,
and both can be turned off.

See [docs/security.md](docs/security.md) for what each guard actually does.
