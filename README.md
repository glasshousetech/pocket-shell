# Pocket Shell

A fast, good-looking terminal emulator for Android — by Glass House Technologies.

Pocket Shell pairs a **real PTY-backed terminal** (so `vim`, `ssh`, `htop`, `less`, and
tab-completion all work) with a modern **Tab Rail** multi-session UI and a Kali-style
frosted-glass look. The goal is a terminal that feels like a native app, not a
1990s console bolted onto a phone.

## Why Pocket Shell over the alternatives

- **Tab Rail** — multiple live shells in one window, switch instantly. No swipe-drawer.
- **Frosted glass** — real window blur (Android 12+) so the terminal sits over your
  wallpaper instead of a flat black rectangle.
- **On-screen extra keys** — ESC / TAB / CTRL / arrows / pipe / slash, plus a
  Ctrl-combo pad (^C ^D ^Z ^L ^A ^E ^R), because phones don't have those keys.
- **Pinch to zoom** the font, sticky modifiers, copy/paste — the ergonomics you expect.

## Architecture

| Layer | What | Where |
|---|---|---|
| UI chrome | Jetpack Compose — Tab Rail, extra keys, theming | `MainActivity.kt`, `ui/Theme.kt` |
| Terminal view | `TerminalView` embedded via `AndroidView` | `MainActivity.kt` |
| View glue | focus, pinch-zoom, modifier keys, clipboard | `TermView.kt` |
| Session engine | PTY spawn + VT100/xterm emulation | `TermCore.kt` → Termux libs |

The terminal engine is **Termux's** `terminal-view` / `terminal-emulator` libraries
(prebuilt native PTY, all ABIs, pulled from JitPack). Pocket Shell wraps that proven core
in its own UI rather than reimplementing a terminal emulator from scratch.

## Build

Everything builds in CI — no Android SDK/NDK needed locally to ship. To build yourself:

```bash
./gradlew :app:assembleDebug     # unsigned debug APK → app/build/outputs/apk/debug/
```

- **CI** (`.github/workflows/ci.yml`) assembles the debug APK on every push and uploads it.
- **Releases** (`.github/workflows/release.yml`) build a signed APK + AAB on a `v*` tag,
  publish to GitHub Releases, and push to the Play Store internal track.
  Requires `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD` (and optional `PLAY_SERVICE_ACCOUNT_JSON`) as repo secrets.

`minSdk 26 · targetSdk 34 · applicationId network.ght.pocketshell`

## Roadmap

- [x] Real PTY terminal engine (Termux `terminal-view`)
- [x] Tab Rail multi-session UI + frosted glass
- [x] On-screen extra keys + Ctrl pad, pinch-to-zoom, clipboard
- [x] CI build + signed release pipeline
- [x] **Userland / package manager** — tap the 🐧 tab to pick a distro (**Alpine
      Linux** or **Ubuntu**, both sha256-pinned) and install a proot-mounted
      rootfs on first use. Real `apk`/`apt`, `python`, `git`, `ssh`, `vim`.
      Long-press the 🐧 tab to uninstall and pick again. proot ships as bundled
      native libs; see `Userland.kt` / `Bootstrap.kt` / `LinuxUi.kt` and
      `THIRD_PARTY.md`.
- [x] **Text selection** — long-press to select a word, drag the handles to
      extend, floating Copy/Paste toolbar, tap elsewhere to deselect (built on
      terminal-view's own `TextSelectionCursorController`; see `TermView.kt`).
- [x] **Color themes** — Kali default, Dracula, Solarized Dark, Nord, Gruvbox
      Dark. Tap the 🎨 tab; persisted across restarts. See `TermTheme.kt` /
      `ThemeUi.kt`.
- [x] Session persistence across process death (best-effort scrollback replay; see `SessionStore.kt`)
- [ ] Configurable extra-keys row
- [ ] Background install progress bar polish (currently a blocking overlay)

## License

Pocket Shell is licensed under the **GNU General Public License v3.0** (see `LICENSE`).

It builds on Termux's terminal libraries (GPLv3) and bundles Android `proot` (GPLv2);
the Alpine userland (MIT) is downloaded on demand. Because Pocket Shell links the Termux
terminal engine, the app as a whole is distributed under GPLv3. Full attribution in
`THIRD_PARTY.md`.
