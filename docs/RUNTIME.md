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
6. Disable/re-enable networking, reconnect, and confirm `tmux new-session -A
   -s agents` returns to the running agent rather than starting over.
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
- Existing pre-v0.4 rootfs installs are health-checked and repaired in place so
  `~/.ssh` survives, while legacy `sh` session snapshots migrate to Linux.
- A fresh install generates Ed25519 identity files at modes 0600/0644. Connect
  explicitly uses the private key with `IdentitiesOnly=yes` and safely accepts a
  new host key while continuing to reject a changed host key.
- Imported private keys are capped at 64 KiB, format-checked, and written as
  `~/.ssh/id_ed25519` with directory mode 0700 and file mode 0600. Any stale
  public half is deleted and re-derived before Connect becomes available.

## v0.4 acceptance status

- [x] Kotlin compile, JVM SSH-profile tests, and debug APK assembly.
- [x] Current ARM64 Ubuntu and Alpine download bytes match the pinned SHA-256 values.
- [x] Debug APK installs on the Android 14 x86_64 emulator.
- [ ] Healthy emulator visual pass. The current shared-host AVD's System UI ANR'd;
  no Pocket Shell screenshot from that run is accepted as evidence.
- [ ] Required ARM64 phone Linux command round-trip.
- [ ] Required phone-to-droplet SSH, remote agent CLI, and tmux reconnect path.
