# Keybindings

`/hotkeys` prints this table inside pijota. `/help` includes it after the commands.

## Editing

| Key | Does |
|---|---|
| `Enter` | send |
| `Ctrl+J` | newline |
| `Tab` | complete; on an empty prompt, accept a `--suggest` offer |
| `Ctrl+K` | delete to end of line |
| `Ctrl+U` | clear the line |
| `Ctrl+W` | delete the word before the cursor |
| `Ctrl+A` / `Ctrl+E` | start / end of line |
| `Ctrl+_` | undo |
| `Alt+_` | redo |
| `Ctrl+Y` | yank the last kill |
| `Alt+Y` | yank-pop |
| `Ctrl+G` | open the draft in `$EDITOR` |
| `→` / `End` | at the end of the line, accept the ghost suggestion |

## Navigating

| Key | Does |
|---|---|
| `Up` / `Down` | history, or move within a list |
| `Ctrl+R` | reverse history search |
| `@` | fzf file completion, inserted as an `@ref` |
| `Ctrl+T` | fzf path completion, inserted raw |
| `Ctrl+P` | command palette |
| `Ctrl+L` | model picker |
| `Ctrl+Shift+P` | next enabled model |
| `Shift+Tab` | cycle thinking level |
| `Ctrl+O` | expand or collapse the status bar |

## Running

| Key | Does |
|---|---|
| `Esc` | interrupt; press twice to force |
| `Ctrl+C` | clear the line; twice in quick succession exits |
| `Ctrl+B` twice | send the running command to the background (needs the `jobs` extension) |

Inside tmux, `Ctrl+B` is tmux's own prefix. `/bg` does the same thing.

In the session picker, `Enter` loads and `Ctrl+D` deletes.

## Remapping

`~/.pijota/keybindings.json` maps an action to a key, or to several.

```json
{
  "palette": "ctrl+t",
  "model": ["ctrl+l", "ctrl+m"]
}
```

A malformed file is ignored rather than fatal: pijota starts with the defaults.

Recognised key names:

`ctrl+g`, `ctrl+l`, `ctrl+o`, `ctrl+p`, `ctrl+t`, `ctrl+shift+p`, `shift+tab`, `alt+up`,
`alt+enter`, `up`, `down`, `left`, `right`, `home`, `end`, `pageup`, `pagedown`, `enter`, `tab`,
`backspace`.

Modifier notation is normalised for the platform when displayed, so a Mac shows `⌘`-style labels
where they apply. Extensions can register their own bindings; `/help` lists them with the rest.
