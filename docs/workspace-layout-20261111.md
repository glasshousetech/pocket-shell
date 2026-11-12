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
