# Pocket Shell runtime contract

Pocket Shell is only ready for release when its primary workflow is exercised
on an ARM64 Android phone. A successful Gradle build is not acceptance.

## Primary acceptance path

1. Fresh-install the signed APK on an ARM64 phone.
2. Choose the recommended Ubuntu setup and let it finish. Completion means the
   app has verified Bash, OpenSSH, tmux, Git, Python, Node/npm, both editors,
   common file/process tools, and a matching app-managed SSH keypair inside proot.
3. In the terminal run `ssh-keygen -lf ~/.ssh/id_ed25519.pub` and
   `command -v bash ssh git python3 node npm vim nano tmux`. Every command must
   succeed; there must be no Android toybox `sh` tab anywhere in the workflow.
4. Open **Connect**, tap **Copy public key for server access**, add only that
   public key to the target account's `authorized_keys`, then choose **Agent
   droplet**. A private-key import is an optional migration path, not setup.
5. Reach `connor@agent.ght.network`, start an agent CLI,
   background Pocket Shell, lock the phone, and return to the same live CLI.
6. Open two connections to the same host. Confirm their `GHT_TAB_ID` values,
   remote shell PIDs and state are independent. Disable/re-enable networking,
   reconnect the original tab, and confirm its UUID-scoped tmux session returns
   to the same running agent without changing the sibling tab.
7. Verify ESC, TAB, Ctrl-C, Ctrl-D, arrows, copy/paste, selection, and IME resize.
8. Long-press the Linux tab, run **Self-test**, and confirm it reports `Ready`.

## Failure history

- v0.2 provisioned required and optional packages in one transaction, swallowed
  the failure, and still wrote the installed marker. One unavailable optional
  package could therefore leave an apparently successful install without SSH.
- v0.2 called `readText()` on provisioning stdout before `waitFor(timeout)`, so
  a hung package manager could block forever and the timeout never ran.
- v0.3 restored a `SessionMode.SYSTEM` snapshot and reset the active tab to it.
  That Android toybox shell had only `/system/bin:/system/xbin`, so commands such
  as `ssh-keygen` were missing even when a Linux rootfs existed.
- v0.3 private-key import could retain the prior identity's `.pub` file. The
  copied public key could therefore differ from the private key selected by SSH,
  producing `Permission denied (publickey)` even after server enrollment.
- v0.4 initially assumed Canonical's Ubuntu Base archive contained an APT
  sources file. It does not: it is a deliberately bare root filesystem. Repair
  therefore ran `apt-get update` with no repositories and could not restore the
  toolchain. Pocket Shell now writes a signed, app-owned `pocketshell.sources`
  file using `archive.ubuntu.com` for x86 and `ports.ubuntu.com` for ARM, then
  makes provisioning use that file exclusively.
- `PROOT_NO_SECCOMP=1` must not be set. It made the x86_64 emulator's legacy
  `poll`/`fork` path fail with `Function not implemented`.
- Android x86_64 emulator processes run under a zygote seccomp filter that can
  reject legacy syscalls used by the guest. Use it for UI/install-flow checks;
  the required Linux/SSH acceptance target is ARM64 hardware.

## Setup invariants (v0.4+)

- Ubuntu is the compatibility-first default; Alpine remains a lightweight option.
- Every user-facing terminal session is Linux; the Android system shell is kept
  only as internal terminal-engine test plumbing.
- Bash, OpenSSH, CA certificates, curl, tmux, Git, Python, Node/npm, editors,
  build tools, and common Unix utilities are mandatory.
- Setup commands have real wall-clock timeouts while stdout drains concurrently.
- Every Ubuntu apt run starts with `dpkg --configure -a` so a rootfs whose
  previous install or repair was interrupted (timeout, swipe-away, reboot) is
  recovered instead of failing with "dpkg was interrupted".
- Existing pre-v0.4 rootfs installs are health-checked and repaired in place so
  `~/.ssh` survives, while legacy `sh` session snapshots migrate to Linux.
- A fresh install generates Ed25519 identity files at modes 0600/0644. Connect
  explicitly uses the private key with `IdentitiesOnly=yes` and safely accepts a
  new host key while continuing to reject a changed host key.
- Imported private keys are capped at 64 KiB and staged at mode 0600 in the
  private mode 0700 SSH directory. `ssh-keygen` must parse the staged key and
  derive its public half before atomic replacement of `~/.ssh/id_ed25519`.
  Rejected inputs preserve the old identity. Interrupted public-metadata
  replacement leaves missing, regenerable metadata rather than a mismatched
  public key. Encrypted-key support and managed backup/export are not provided;
  keep a secure external backup before replacing a working identity.

## 0.5.4 workspace overhaul

The approved release candidate has build code 22 (private review used21). Synthetic
Android PTYs verify terminal/UI mechanics; server-side SSH checks verify
UUID-isolated remote processes and reconnect. Neither substitutes for the
ARM64 acceptance path above. Physical Fold access, valid-key import on ARM64,
production-signed upgrade, network/screen-off behavior and live AI-provider
responses remain pending.

Selected tabs, reviewed drafts, their delivery errors and AI request/results
live with the foreground service across Activity recreation. They are not
written to diagnostic logs or Android saved-state Bundles. Process death is
different: saved terminal snapshots can restore historical output into fresh
local shells, but cannot restore a live PTY or an in-memory unsent draft.

An ended tab is still an existing workspace. Activity startup must not run
first-use setup or close service-owned tabs merely because none is alive.
Doing so discarded ended-session history, drafts and their delivery errors.
The workspace regression exercises an ended shell, rejected Insert and
Activity recreation together; cancellation and renamed-tab close prompts are
also exercised through the native UI.

AI insertion accepts only a reviewed single-line command, only in its original
live tab. Multiline/control-containing suggestions remain visible and are
rejected without rewriting or submission. Insert does not send Enter; Run is
explicit. Requests disclose that up to 4,000 characters of terminal context
are included. Provider credentials and text remain excluded from diagnostics.

Settings → Diagnostics retrieves bounded local operational logs (two 128 KiB
files, bounded asynchronous queue). Events include app/build, UTC timestamp,
component, event, session correlation and relevant tab/exit context, without
terminal output, commands, connection addresses or key material.

## v0.4 acceptance status

- [x] Kotlin compile, JVM SSH-profile tests, and debug APK assembly.
- [x] Current ARM64 Ubuntu and Alpine download bytes match the pinned SHA-256 values.
- [x] Debug APK installs on the Android 14 x86_64 emulator.
- [ ] Healthy emulator visual pass. The current shared-host AVD's System UI ANR'd;
  no Pocket Shell screenshot from that run is accepted as evidence.
- [ ] Required ARM64 phone Linux command round-trip.
- [ ] Required phone-to-droplet SSH, remote agent CLI, and tmux reconnect path.
