# Third-party components

Railterm bundles and downloads the following. Railterm itself is GPLv3 (see `LICENSE`).

## Terminal engine — Termux
`com.termux.termux-app:terminal-view` / `terminal-emulator` (JitPack, v0.118.0).
Native PTY + VT100/xterm emulator. **GPLv3** (underlying *Terminal Emulator for
Android* code is Apache-2.0). Source: https://github.com/termux/termux-app

## proot — bundled native binaries
`app/src/main/jniLibs/<abi>/libproot.so`, `libproot-loader.so`, `libproot-loader32.so`.
Android builds of proot (userland loader variant), from
https://github.com/green-green-avk/build-proot-android
(proot upstream: https://github.com/proot-me/proot). **GPLv2.**
These are the only files Android permits an app to execute, so they ship in the
native-lib dir rather than being downloaded.

## Alpine Linux — downloaded at first use (optional distro)
`alpine-minirootfs-3.20.10-<arch>.tar.gz`, fetched from
https://dl-cdn.alpinelinux.org and verified against a pinned sha256 (see
`Userland.kt`). Alpine is MIT-licensed; bundled packages carry their own licenses.
Not redistributed in the repo — downloaded on demand.

## Ubuntu — downloaded at first use (optional distro)
`ubuntu-base-24.04.4-base-<arch>.tar.gz`, Canonical's official "Ubuntu Base"
tarballs, fetched from https://cdimage.ubuntu.com/ubuntu-base/ and verified
against a pinned sha256 independently recomputed from the downloaded tarball
(see `Userland.kt`). Ubuntu is licensed under a mix of open-source licenses
(GPL, LGPL, etc. per package) — see https://ubuntu.com/legal. Not redistributed
in the repo — downloaded on demand.
