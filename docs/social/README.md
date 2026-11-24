# Pocket Shell social & store artwork

Ready-to-post images for Pocket Shell in the **Glass Tech** identity (the September 2026
"Prism" rebrand). Everything here is rendered from one template, so change the template and
re-run the script rather than editing PNGs by hand.

| File | Size | Use it for |
|---|---|---|
| `pocket-shell-social-preview.png` | 1280 × 640 | GitHub repository social preview (repo **Settings → General → Social preview**, manual upload; GitHub has no API for it) |
| `pocket-shell-og.png` | 1200 × 630 | Link cards and image posts on LinkedIn, X, Facebook; Open Graph image for any page about the app |
| `pocket-shell-square.png` | 1080 × 1080 | Square feed posts (Instagram, Facebook, LinkedIn) |
| `pocket-shell-play-feature.png` | 1024 × 500 | Google Play Console feature graphic |

Once this is on `master`, each image also has a stable public URL that can be pasted straight
into the ght.network admin **Social Media** hub as a post's image URL, or into any OG tag:

```
https://raw.githubusercontent.com/glasshousetech/pocket-shell/master/docs/social/pocket-shell-og.png
https://raw.githubusercontent.com/glasshousetech/pocket-shell/master/docs/social/pocket-shell-square.png
https://raw.githubusercontent.com/glasshousetech/pocket-shell/master/docs/social/pocket-shell-social-preview.png
https://raw.githubusercontent.com/glasshousetech/pocket-shell/master/docs/social/pocket-shell-play-feature.png
```

## Regenerate

```bash
npm i -D playwright && npx playwright install chromium   # once
node scripts/render-social.cjs                            # all four
node scripts/render-social.cjs og square                  # a subset
```

The script opens `src/pocket-shell-card.html?f=<format>` in headless Chromium at the exact
pixel size of each format and screenshots it, so the PNGs are always the size the platform
asks for. It fails loudly if a font, screenshot or brand file does not load.

## What is in the card, and where it came from

- **Company lockup** — `src/brand/glass-tech-pyramid-lockup-dark.svg` is the approved Glass Tech
  artwork copied byte-for-byte from the website repo (`ght-network`,
  `storefront/public/brand/`, git blob `269f6b57…`). The card uses
  `glass-tech-pyramid-lockup-dark-transparent.svg`, which is the same file with only its
  black background rectangle removed so it can sit on the app's own surface colour.
  Per the brand notes, the standalone mark is the solid pyramid and the sliced stroke peak
  appears only in the A of the wordmark.
- **Palette** — Glass Tech paper `#F4F4F4`, cyan `#5FE1FF`, violet `#7A3CFF`, magenta `#FF2D6F`.
  The cyan→violet→magenta gradient is used only as the brand rail along the bottom edge.
  The dark surface `#0C0E13` and the `#5B7FFF` prompt glyph are Pocket Shell's existing app
  identity, which the rebrand deliberately left as-is.
- **Type** — Inter (brand typeface) for the headline and tagline, JetBrains Mono (the app's
  own terminal font, from `app/src/main/res/font/`) for the eyebrow, chips and URL.
- **Copy** — the tagline and the three highlight chips are the approved wording already
  live on `ght.network/software/pocketshell`.
- **Screenshot** — `src/shots/pocket-shell-tab-rail.png` is the real capture used on the
  website's Pocket Shell page (Tab Rail with two live shells and a restored session), not a
  mock-up.

## Adding a format

Add an entry to `FORMATS` in `scripts/render-social.cjs` (name, width, height, file name) and,
if the proportions need it, a `body[data-f="<name>"]` block in the template's CSS. The
`preview`, `og` and `feature` formats share the wide layout; `square` stacks the copy over the
phone.
