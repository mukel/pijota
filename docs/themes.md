# Themes

Four ship: `dark`, `light`, `gruvbox-dark`, `gruvbox-light`. `/theme` lists and switches them
live, with no restart.

```bash
pj --theme gruvbox-dark
pj --theme ./my-theme.json      # a path loads and selects it
pj --theme auto                 # follow the terminal's background
pj --no-themes                  # skip discovery entirely
```

```json
{ "theme": "gruvbox-dark", "themes": ["~/work/corporate.json"] }
```

Unset means `auto`. Pijota reads `COLORFGBG` to tell a light terminal from a dark one, and honours
`NO_COLOR` by dropping colour altogether.

## Where they come from

| Location | Scope |
|---|---|
| built in | `dark`, `light`, `gruvbox-dark`, `gruvbox-light` |
| `~/.pijota/themes/*.json` | every project |
| `--theme <path>` | that run |

The file name is the theme name.

## File format

Three sections. `vars` names colours once, `colors` assigns them to slots, `export` styles the
HTML export.

```json
{
  "name": "my-theme",
  "vars": {
    "accent": "#8abeb7",
    "red": "#cc6666",
    "text": "#d4d4d4"
  },
  "colors": {
    "accent": "accent",
    "error": "red",
    "text": "text"
  },
  "export": {
    "pageBg": "#18181e",
    "cardBg": "#1e1e24",
    "infoBg": "#3c3728"
  }
}
```

A slot takes either a var name or a literal `#rrggbb`. Slots you leave out fall back to the
built-in dark theme, so a partial theme is valid -- but a theme that omits `text` colours
everything except the thing you are reading, which is why the vendored Catppuccin themes were
dropped in v0.2.0.

## Slots

| Group | Slots |
|---|---|
| base | `accent`, `borderAccent`, `success`, `error`, `warning`, `muted`, `dim`, `text` |
| messages | `userMessageBg`, `userMessageText`, `customMessageBg`, `customMessageText`, `customMessageLabel`, `selectedBg` |
| thinking | `thinkingText`, `thinkingOff`, `thinkingMinimal`, `thinkingLow`, `thinkingMedium`, `thinkingHigh`, `thinkingXhigh`, `thinkingMax` |
| tools | `toolTitle`, `toolOutput`, `toolPendingBg`, `toolSuccessBg`, `toolErrorBg`, `toolDiffAdded`, `toolDiffRemoved`, `toolDiffContext` |
| markdown | `mdHeading`, `mdLink`, `mdLinkUrl`, `mdCode`, `mdCodeBlock`, `mdCodeBlockBorder`, `mdQuote`, `mdHr`, `mdListBullet` |
| syntax | `syntaxComment`, `syntaxKeyword`, `syntaxFunction`, `syntaxVariable`, `syntaxString`, `syntaxNumber`, `syntaxType`, `syntaxOperator`, `syntaxPunctuation` |
| other | `bashMode` |

The seven `thinking*` slots are a ramp: pijota expects them to get warmer or brighter as the level
rises, since the caret colour is how the current level is read at a glance.

`export` takes literal colours only: `pageBg`, `cardBg`, `infoBg`.

## Prompt glyphs

Separate from colour, under `prompt` in [settings](settings.md#prompt): the caret, the error and
question carets, the continuation bar, the rail, and whether the frame draws its rules.
