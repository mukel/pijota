# Terminal setup

Pijota works out of the box in any terminal. This page is for the cases where a
key does nothing, or does the wrong thing.

## Why modifiers are unreliable

A traditional terminal sends keys as bytes, and that encoding has gaps:

- `Shift+Enter` and `Enter` are the same byte. A plain terminal cannot tell you
  which one was pressed.
- `Esc` and `Alt+key` both start with the same byte. Telling them apart means
  waiting a few milliseconds to see whether more bytes follow, and guessing if
  they don't. Over a slow SSH link that guess is sometimes wrong.
- `Ctrl+Shift+P` and `Ctrl+P` collapse together on many terminals.

Pijota asks the terminal to use the
[Kitty keyboard protocol](https://sw.kovidgoyal.net/kitty/keyboard-protocol/),
which reports the key and its modifiers explicitly, so none of that guessing is
needed. It is negotiated at startup: pijota requests it, asks what the terminal
actually enabled, and adapts. Terminals that don't support it are unaffected,
and pijota pops the mode back off on exit and whenever it hands the terminal to
another program.

To see whether your terminal accepted it, open `/logs` and look for
`kitty keyboard protocol: enabled`.

## Terminals that support it

Kitty, Ghostty, WezTerm, foot, iTerm2, Alacritty (0.13+), and recent Windows
Terminal all support the protocol. So does tmux 3.4+, but only if you turn it
on:

```conf
# ~/.tmux.conf
set -g extended-keys on
set -as terminal-features 'xterm*:extkeys'
```

Without those lines tmux swallows the extended keys and pijota falls back to the
legacy encoding.

## Terminals that don't

Everything still works; you lose only the keys the legacy encoding cannot
express. `Shift+Enter` will submit rather than insert a newline, so use one of
these instead:

| Instead of    | Use                     |
| ------------- | ----------------------- |
| `Shift+Enter` | `Alt+Enter` or `Ctrl+J` |

Apple Terminal is the common case here: it has no support for the protocol and
no way to add it. Use iTerm2 or Ghostty on macOS if you want `Shift+Enter`.

## Alt+Backspace

`Alt+Backspace` deletes the word before the cursor, the same as `Ctrl+W`. Some
terminals send nothing at all for it. On Ghostty, add:

```conf
keybind = alt+backspace=text:\x1b\x7f
```

## Colors

Pijota reads the terminal's background to pick a light or dark theme. If it
guesses wrong, pin it:

```bash
pijota --theme dark
```

`--theme auto` restores the automatic behaviour, and `/theme` switches
interactively. See the theme list with `/theme` and no argument.

## Nothing renders correctly

If output is garbled rather than merely missing a key, the terminal is probably
reporting a size or capability that doesn't match reality. Check:

```bash
echo $TERM        # should not be "dumb"
tput colors       # 256 or more for the default themes
```

Running pijota over a serial console or in a CI log is supported, but it detects
the non-interactive case and switches to plain output; use `-p` explicitly for
scripts.
