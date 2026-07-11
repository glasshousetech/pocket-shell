# Railterm

A fast, good-looking terminal emulator for Android — by Glass House Technologies.

Railterm pairs a **real PTY-backed terminal** (so `vim`, `ssh`, `htop`, `less`, and
tab-completion all work) with a modern **Tab Rail** multi-session UI and a Kali-style
frosted-glass look. The goal is a terminal that feels like a native app, not a
1990s console bolted onto a phone.

## Why Railterm over the alternatives

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
(prebuilt native PTY, all ABIs, pulled from JitPack). Railterm wraps that proven core
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

`minSdk 26 · targetSdk 34 · applicationId com.railterm.app`

## Roadmap

- [x] Real PTY terminal engine (Termux `terminal-view`)
- [x] Tab Rail multi-session UI + frosted glass
- [x] On-screen extra keys + Ctrl pad, pinch-to-zoom, clipboard
- [x] CI build + signed release pipeline
- [ ] Text selection toolbar polish + URL long-press
- [ ] Session persistence across process death
- [ ] Color themes + configurable extra-keys row
- [ ] **Userland / package manager** (`apt`, `git`, `python`) — the big one; Termux
      ships a bootstrap, Railterm currently runs on the Android system shell. Likely a
      `proot`-distro path. Tracked as its own epic.

## License

Railterm is licensed under the **GNU General Public License v3.0** (see `LICENSE`).

It builds on Termux's terminal libraries, which are GPLv3 (with the underlying
*Terminal Emulator for Android* code under Apache-2.0). Because Railterm links the
Termux terminal engine, the app as a whole is distributed under GPLv3.
