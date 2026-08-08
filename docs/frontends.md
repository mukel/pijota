# Frontends

One agent core, six ways in. The TUI is the default; the rest are positional subcommands or a
`--mode`. Session, model, tool and extension flags apply to all of them.

| Frontend | Start with |
|---|---|
| terminal UI | `pj` |
| print | `pj -p "..."`, or pipe stdin |
| [JSON events](json-mode.md) | `pj -p "..." --mode json` |
| [RPC](rpc-mode.md) | `pj --mode rpc` |
| web | `pj web [host:port]` |
| Telegram | `pj telegram` |
| editor (ACP) | `pj acp` |

## Web

```bash
pj web                    # 127.0.0.1:8420 by default
pj web 0.0.0.0:8420       # reachable from your phone
```

Prints the URL and a scannable QR. Binding past loopback mints a **192-bit pairing token**,
appended to the URL as `?t=…` and then pinned as a `pijota_rc` cookie so the secret leaves the
address bar. Every request must carry it. On loopback there is no token, because there is nobody
else on the interface.

The page streams the live session: messages, tool cards with their output as it arrives, dialogs
for the `question` tool, and the same slash commands as the TUI.

`/remote-control` from inside a running TUI does the same thing for the session you already have
open, rather than starting a new one. It offers four channels, ordered safest-first:

- **Tailscale** (your devices only) — binds loopback and runs a foreground `tailscale serve` child, for an
  `https://<node>.<tailnet>.ts.net` address reachable only from your own tailnet. WireGuard-encrypted, TLS is
  Tailscale's, no third-party edge, and the mapping dies with pijota. Needs `tailscaled` running, Serve + HTTPS
  certificates enabled in the admin console, and your user as operator (`tailscale set --operator=$USER`).
- **Tunnel** (public internet) — binds loopback and puts a `cloudflared` quick tunnel in front, for a public
  `trycloudflare.com` URL. TLS terminates at Cloudflare's edge, which is in the trust path.
- **Telegram** — the bot, below.
- **Web** (same wifi only) — binds all interfaces; reachable from anything on the LAN. The token travels in cleartext,
  so only use this on a network you trust.

> [!NOTE]
> The token authenticates, it does not encrypt. The Tailscale and Tunnel channels add real encryption on top; the LAN
> Web channel does not, so reach for Tailscale (or `ssh -L`) on any network you do not fully trust.

## Telegram

```bash
export TELEGRAM_BOT_TOKEN="…"   # from @BotFather
export TELEGRAM_CHAT_ID="…"     # the one chat this bot answers
pj telegram
```

Both are required, and pijota refuses to start without them: this build is **single-chat** by
design, so the bot cannot be added to a group and left answering strangers. Find your chat id by
messaging the bot and reading `getUpdates`, or from any of the id bots.

Rate limits are honoured properly here -- Telegram returns `retry_after` on a 429 and pijota waits
exactly that long.

## Editor (ACP)

```bash
pj acp
```

Agent Client Protocol over stdio, for Zed, JetBrains AI Assistant, Neovim and anything else that
speaks it. The editor launches the process; you never run this by hand. Pijota publishes its slash
commands so they appear in the editor's `/` menu, and permission requests surface as editor
prompts rather than terminal ones.

Point your editor's agent config at the `pj` binary with `acp` as its only argument.

`PIJOTA_ACP_WIRELOG=1` logs the protocol traffic when something does not connect.

## Print and pipes

```bash
pj -p "explain this repo"
cat error.log | pj -p "what broke?"     # stdin and the prompt compose
pj -p "..." --mode json | jq            # one event per line
```

Piped stdin and a prompt argument compose: the pipe carries the data, the argument the
instruction. An alias named `print` in [settings](settings.md#model) selects a cheaper model for
one-shot runs without changing your default.
