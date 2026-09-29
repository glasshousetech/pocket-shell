# GHT shared themes

`ght-themes.json` is the portable source of truth for 20 complete palettes. Pocket Shell 0.5.0 is the first consumer. Other applications can use the same stable IDs and semantic roles while retaining their own navigation, typography, icons, and approved brand assets.

Run `python3 scripts/generate-themes.py` from the repository root after editing the catalog. `--check` validates contrast, IDs, ANSI completeness, and generated-file freshness; CI runs it before the Android build.

- Android: generated `ThemeCatalog.kt` supplies Compose chrome and the Termux palette.
- Web: import `ght-themes.css` and set `data-ght-theme="ght_signature"` on the app root. Use `--ght-background`, `--ght-surface`, `--ght-control`, `--ght-text`, `--ght-muted`, `--ght-accent`, `--ght-on-accent`, and the semantic roles.
- Other platforms: read RGB hex values from the JSON. `mode` controls platform light/dark behavior; `terminal` supplies all 16 ANSI colors and defaults.

Text, muted text, accent, error, success, warning, and info meet at least 4.5:1 contrast on the background, surface, alternate surface, and control backgrounds. On-accent text meets the same threshold. Terminal foreground is validated separately. ANSI palettes remain conventional (including black/white); arbitrary terminal programs can still request their own 256-color/true-color values or OSC overrides.

## Catalog

| Theme | Appearance |
|---|---|
| GHT Signature | Charcoal, warm white, GHT gold |
| Obsidian | True black with ice cyan |
| Graphite | Neutral slate and silver |
| Blueprint | Midnight blue and clear blue |
| Deepwater | Ocean teal |
| Forest | Pine and moss |
| Ember | Copper and warm charcoal |
| Nightshade | Deep plum and lavender |
| Phosphor | True black with terminal green |
| Amber | Warm retro amber |
| Porcelain | White and cobalt |
| Linen | Warm paper and walnut |
| Arctic | Frost blue and navy |
| Rose Quartz | Blush and mulberry |
| Sage | Pale botanical and evergreen |
| Pocket Shell | Original indigo and xterm |
| Dracula | Original terminal palette with matching app chrome |
| Solarized Dark | Original terminal palette with matching app chrome |
| Nord | Original terminal palette with matching app chrome |
| Gruvbox Dark | Original terminal palette with matching app chrome |

The five historical IDs and terminal palettes are preserved, including `default`. Unknown IDs fall back to `default`. Theme changes neither migrate session data nor reconnect SSH. Selections are stored locally per app, not synchronized between products. This repository delivers reusable adapters; an app is only considered rolled out after its own integration and deployment are verified.

Classic palette provenance is retained from Pocket Shell's previous release: [Dracula](https://draculatheme.com/terminal), [Solarized](https://ethanschoonover.com/solarized/), [Nord](https://www.nordtheme.com/docs/colors-and-palettes), and [Gruvbox](https://github.com/morhetz/gruvbox). The xterm default comes from the pinned Termux library. The other fifteen palettes are original GHT selections.
