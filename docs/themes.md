# Themes

Four ship: `dark`, `light`, `gruvbox-dark`, `gruvbox-light`. `/theme` switches live.

```bash
pj --theme gruvbox-dark
pj --theme ./my-theme.json      # a path loads and selects it
pj --theme auto                 # follow the terminal's background
pj --no-themes                  # skip discovery entirely
```

```json
{ "theme": "gruvbox-dark", "themes": ["~/work/corporate.json"] }
```

Unset means `auto`, which reads `COLORFGBG`. `NO_COLOR` drops colour entirely.

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

A slot takes a var name or a literal `#rrggbb`. Omitted slots fall back to `dark`, so a partial
theme is valid; omitting `text` colours everything except what you are reading.

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

The seven `thinking*` slots should ramp: the caret colour is how the current level is read.
`export` takes literal colours only.

## Prompt glyphs

Not part of the theme: [`prompt` in settings](settings.md#prompt).
