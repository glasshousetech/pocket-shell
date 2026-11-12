# Pocket Shell 0.7.0: one toolbar, adjustable sizes, themes whose regions stay apart

Connor, November 11, 2026, on the Fold7 with 0.6.0:

1. The first row of toolbar buttons (History, Live, Actions, Text / voice) is never used and is in
   the way. Move those buttons to the very end of the collapsible toolbar.
2. The workspace menu on the left collapses, but while it is open its size cannot be adjusted.
   Every toolbar and menu should be adjustable.
3. The themes mesh together. With some themes set, the workspace menu blends into the rest of the
   page and design elements cannot be told apart.

## What changed

**One toolbar.** The fixed row above the keys is gone. History ↑, History ↓, Live, Actions and
Text / voice are now ordinary entries in the extra-keys catalog (`ExtraKeysLayouts.TOOLS`), placed
at the end of every preset behind a hairline. Because they are catalog keys they can be reordered,
removed and re-added from the keys dialog like anything else. A row someone already customized
gains them once, at the end (`extra_keys_tools_added`); removing them afterwards sticks. Live still
hides while a full-screen program owns the screen. The toolbar's open or folded state is now
remembered (`toolbar_open`), since it holds the way into Actions.

**Adjustable sizes.** Two drag handles, each with the same value available in Settings ›
Appearance › Layout sizes and exposed to screen readers as named actions:

| What | How | Range | Why the range |
|---|---|---|---|
| Workspace menu width (wide screens) | Drag the grip on the menu's right edge; double-tap resets | 160 dp to screen width minus 240 dp, default 220 | Below 160 dp the active tab's name has no room beside its two 48 dp buttons (the collapse button gives the 60 dp rail instead). The terminal keeps 240 dp so a prompt stays usable. |
| Toolbar key size | Drag the toolbar's handle up or down; also in the keys dialog | 32 to 96 dp, default 48 | Below 32 dp a fingertip covers neighbouring keys. Above 96 dp two keys no longer fit across the folded cover screen. |

The menu's width is applied when the drag ends, with a guide line following the finger. Changing
the terminal's column count reflows its whole scrollback, so resizing on every frame of a drag
would repeat exactly the main-thread work `performance-20261109.md` removed. Key size applies live:
it only changes the row count, which does not reflow. A saved width larger than the current screen
allows (after folding or rotating) is clamped for display and the preference is kept.

**Themes.** The cause was in the palette data, not the layout: the chrome color (`surfaceAlt`) sat
within 1.01:1 to 1.07:1 of the terminal background in most themes, and nothing drew a line between
regions. Now:

- `themes/ght-themes.json` has a rebuilt elevation ladder for all 20 themes (see `themes/README.md`
  for the rules). Terminal background, foreground, cursor and ANSI colors are untouched, so
  terminal output looks exactly as before.
- `scripts/generate-themes.py` and `TermThemeTest.everyThemeKeepsItsRegionsVisiblyApart` reject any
  catalog whose regions blend, so a future theme cannot bring the problem back.
- The app draws a hairline in the theme's border color between the workspace menu and the terminal
  (phone and wide layouts) and above the toolbar.
- Where the stronger backgrounds would have pushed a text role under 4.5:1 (mostly `muted`), that
  role was moved just far enough to pass again.

## Lessons

- **A contrast rule for text says nothing about regions.** The original validator checked every
  text role against every background and passed themes whose backgrounds were indistinguishable
  from each other. Region separation needs its own rule.
- **Do not apply a width change per drag frame to a terminal.** Column changes reflow history.
  Commit on release, or change only the row count.
- **UI Automator only matches nodes that are on screen.** Once the tools moved to the end of a
  scrolling row, `By.text("Text / voice")` stopped working as the "workspace is ready" signal used
  by eight suites. `ToolbarRobot` (androidTest) now provides that signal from the always-present
  handle and reaches a tool by opening and scrolling the row, re-reading after each scroll because
  Compose rebuilds accessibility nodes while a scroll settles.
- **`UiDevice.drag` holds a long-press before moving.** On the toolbar handle that opens the keys
  dialog. Use `swipe` to exercise a Compose `draggable` that shares a node with a long-click.

## Verification

- Unit tests and lint: pass (theme region rules, tool placement and the custom-row migration included).
- Hosted CI run 43153968055 on the merged commit's parent (`140436d`), API 34 at 320x640 dp: build,
  lint and all 73 instrumented tests pass, including `WorkspaceLayoutUiTest` and
  `WorkspaceControlsUiTest`, which CI now runs.
- agent-prod, private API 34 emulator matching CI: 68 of 68 (every class except the three that
  need CI's file-transfer fixture), including all 20 themes applied to two live sessions.
- agent-prod, private API 35 emulator at 1080x2400 and 1680x1800: 67 of 69. The two others are not
  regressions: `ThemeWorkspaceUiTest` fails at the same line on the untouched 0.6.0 build on that
  image, and `ThemeUpgradeTest` needs the release workflow's arguments.
- The first CI run on this branch (43150730484) failed six tests. All six were the new test helper
  reading API 34's stale accessibility cache; one exposed a real defect on a short screen, where
  the keys dialog's Close button scrolled out of reach. Both are fixed in `140436d`.

## Deployment — 0.7.0, November 12, 2026 (UTC)

Connor: "get all of them completely ready and fully deployed."

- PR #19 merged as `ec92abb`; tag `v0.7.0`; signed release run 43155359613 passed, including the
  same-signer upgrade from the public APK and the signed runtime smoke.
- APK SHA-256 `8ae671487e28232cbe92fcd57abd27f531e3988ff248ef40fffc53974522d5d9`, signer
  `ceee0b98…1420` (identical to the live 0.6.0 APK), versionCode 34, not debuggable.
- Live: `https://dl.ght.network/pocketshell.apk` (stable), immutable
  `https://dl.ght.network/apps/pocketshell/0.7.0-8ae671487e28/pocketshell-0.7.0.apk` (exact path
  added to the Caddy download allow-list), `pocketshell-latest.json` (versionCode 34).
  `scripts/verify-deploy.sh v0.7.0`: OK. GitHub `releases/latest` is v0.7.0, so the in-app updater
  offers it. The 0.6.0 immutable URL still serves.
- Rollback: `/opt/halo-dl/pocketshell.apk.bak-0.6.0-20261112T043855Z`,
  `pocketshell-latest.json.bak-0.6.0-20261112T043855Z` and
  `/etc/caddy/Caddyfile.bak-pocketshell-0.7.0-20261112T043855Z` on gh-cloud-01.
- The website needed no change: ght.network/software/pocketshell links the stable download and
  shows no version number.
- Not done: the Fold7 was not updated remotely, because installing ends running sessions. Update
  from Settings › Updates, or the download link. The drag handles have been exercised on emulators
  only; a finger on the Fold7's inner screen is the remaining check.
