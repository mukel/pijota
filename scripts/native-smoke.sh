#!/usr/bin/env bash
# Smoke-tests the native binary (target/pj) against each provider FAMILY.
# Each family has a distinct native-image surface (reflection metadata for its wire format),
# so one turn per family catches what a single-provider test cannot:
#   - openai-compatible : deepseek/deepseek-v4-flash
#   - anthropic-style   : kimi-coding/k3
#   - gemini            : google/gemini-3.1-flash-lite-preview
# Families whose provider has no configured key are SKIPPED, not failed.
set -u

PJ="${PJ:-target/pj}"
PJ="$(realpath "$PJ" 2>/dev/null || echo "$PJ")"
FAILED=0

# Run every model turn from a neutral temp directory: a project cwd loads its context files (CLAUDE.md
# and friends) into the system prompt, and with that much context small models get pedantic about
# instructions like "reply with the piped word", flaking the assertions for non-code reasons.
WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

say() { printf '%s\n' "$*"; }
fail() { say "FAIL: $*"; FAILED=1; }

[ -x "$PJ" ] || { say "no native binary at $PJ (run: make native)"; exit 1; }

say "== basics =="
"$PJ" --version >/dev/null 2>&1 && say "ok: --version ($("$PJ" --version))" || fail "--version"
"$PJ" --help >/dev/null 2>&1 && say "ok: --help" || fail "--help"
MODELS=$("$PJ" --list-models 2>/dev/null)
[ -n "$MODELS" ] && say "ok: --list-models" || fail "--list-models produced nothing"

say "== provider families =="
run_family() {
    local family="$1" model="$2"
    if ! grep -q "$model" <<<"$MODELS"; then
        say "skip: $family ($model has no configured key)"
        return
    fi
    local out
    out=$(cd "$WORKDIR" && timeout 90 "$PJ" --no-session -m "$model" -p "Reply with exactly: SMOKE-OK" 2>&1)
    if grep -q "SMOKE-OK" <<<"$out"; then
        say "ok: $family ($model)"
    else
        fail "$family ($model): $(tail -2 <<<"$out")"
    fi
}
run_family "openai-compatible" "deepseek/deepseek-v4-flash"
run_family "anthropic-style" "kimi-coding/k3"
run_family "gemini" "google/gemini-3.1-flash-lite-preview"

say "== stdin pipe =="
out=$(cd "$WORKDIR" && echo "SMOKE-PIPE" | timeout 90 "$PJ" --no-session \
    -p "The first line of this message is a code word. Reply with exactly that code word and nothing else." 2>&1)
grep -q "SMOKE-PIPE" <<<"$out" && say "ok: piped stdin reaches the prompt" || fail "stdin pipe: $(tail -2 <<<"$out")"

if [ "$FAILED" = 0 ]; then say "== native smoke: ALL OK =="; else say "== native smoke: FAILURES =="; fi
exit "$FAILED"
