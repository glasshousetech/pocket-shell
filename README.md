# Pocket Shell

A fast, good-looking terminal emulator for Android — by Glass House Technologies.

Pocket Shell pairs a **real PTY-backed terminal** (so `vim`, `ssh`, `htop`, `less`, and
tab-completion all work) with a modern **Tab Rail** multi-session UI and twenty coordinated
app and terminal themes. The goal is a terminal that feels like a native app, not a
1990s console bolted onto a phone.

## Why Pocket Shell over the alternatives

- **Tab Rail** — multiple live shells in one window, switch instantly. No swipe-drawer.
- **Twenty complete themes** — matching app controls, terminal colors, dialogs, and
  system bars, including five light palettes and OLED-black options.
- **On-screen extra keys** — ESC / TAB / CTRL / arrows / pipe / slash, plus a
  Ctrl-combo pad (^C ^D ^Z ^L ^A ^E ^R), because phones don't have those keys.
- **Pinch to zoom** the font, sticky modifiers, copy/paste — the ergonomics you expect.
- **One-tap remote workspaces** — Connect opens `agent.ght.network` or a saved
  SSH host inside a named tmux session, so a mobile-network drop does not kill
  the running agent CLI.
- **GHT machine shortcuts** — Connect has cards for the laptop, MOB, dev, deploy
  and SFO servers, and the same names work typed (`ssh alientop.ght.network`,
  `ssh mob`, `ssh dev`). Each hops through the agent droplet with `ght-hop`, so
  the phone key only needs gh-cloud-01. The aliases live in an app-managed
  `~/.ssh/config.d/pocketshell-ght.conf`; your own `~/.ssh/config` is kept.
- **Linux-only first run** — Ubuntu is recommended and Pocket Shell never drops
  the user into Android's limited toybox shell. Setup must verify the complete
  developer toolchain and an SSH identity before a terminal can open.
- **Guided SSH enrollment** — Pocket Shell generates its own private identity,
  keeps it inside app-private Linux storage, and exposes only a one-tap **Copy
  public key** action for server authorization.
- **Self-healing environment** — long-press the Linux tab to run the same
  toolchain health gate used at startup or repair an incomplete installation.

## Architecture

| Layer | What | Where |
|---|---|---|
| UI chrome | Jetpack Compose — Tab Rail, extra keys, theming | `MainActivity.kt`, `ui/Theme.kt` |
| Terminal view | `TerminalView` embedded via `AndroidView` | `MainActivity.kt` |
| View glue | focus, pinch-zoom, modifier keys, clipboard | `TermView.kt` |
| Session engine | PTY spawn + VT100/xterm emulation | `TermCore.kt` → Termux libs |

The terminal engine is based on **Termux v0.118.0**, with its Java engine, native PTY
bridge and regression tests built from vendored source. Pocket Shell adds an ordered,
nonblocking input queue and a normal keyboard/voice composition path. See the complete
[architecture and improvement roadmap](docs/ARCHITECTURE.md),
[privacy behavior](PRIVACY.md), and [contribution guide](CONTRIBUTING.md).

## Build

CI builds the app with JDK 17 and pinned Android SDK/NDK tools. To build yourself:

```bash
./gradlew :app:assembleDebug     # debug-signed APK → app/build/outputs/apk/debug/
```

- **CI** (`.github/workflows/ci.yml`) runs lint, unit tests and real-PTY/UI instrumentation
  on master pushes and pull requests, and uploads the debug APK and UI evidence.
- **Releases** (`.github/workflows/release.yml`) build a signed APK + AAB on a `v*` tag,
  verify an in-place signed upgrade, and create a **draft** GitHub release. Stable
  download promotion is a separate verified step. Non-RC releases can also upload
  to the Play internal track when its optional credential is configured.
  Requires `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD` (and optional `PLAY_SERVICE_ACCOUNT_JSON`) as repo secrets.

`minSdk 26 · targetSdk 34 · applicationId network.ght.pocketshell`

## Roadmap

- [x] Real PTY terminal engine (Termux `terminal-view`)
- [x] Tab Rail multi-session UI + complete app themes
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
- [x] **Twenty whole-app themes** — tap **Theme** or **Settings → Browse 20 themes**.
      Search and filter by dark, light, or OLED; selections persist across restarts.
      See [the shared catalog](themes/README.md).
- [x] Session persistence across process death (best-effort scrollback replay; see `SessionStore.kt`)
- [x] SSH connection workspace with saved custom host and tmux reconnect
- [x] Bounded, recoverable provisioning with mandatory full-toolchain verification
- [x] App-managed Ed25519 identity, public-key copy workflow, and secure private-key import
- [x] Linux self-test/repair UI and automatic migration away from legacy Android `sh` tabs
- [x] **Configurable extra-keys layouts** — long-press the row's handle to pick
      a preset (Full, Minimal, SSH & tmux) or reorder/toggle individual keys;
      persisted across restarts. Default is the original row. See
      `ExtraKeys.kt` / `ExtraKeysUi.kt`.

## License

Pocket Shell is licensed under the **GNU General Public License v3.0** (see `LICENSE`).

It builds on Termux's terminal libraries (GPLv3) and bundles Android `proot` (GPLv2);
the Alpine userland (MIT) is downloaded on demand. Because Pocket Shell links the Termux
terminal engine, the app as a whole is distributed under GPLv3. Full attribution in
`THIRD_PARTY.md`.
