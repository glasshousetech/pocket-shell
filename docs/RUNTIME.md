# Pocket Shell runtime contract

Pocket Shell is only ready for release when its primary workflow is exercised
on an ARM64 Android phone. A successful Gradle build is not acceptance.

## Primary acceptance path

1. Fresh-install the signed APK on an ARM64 phone.
2. Choose the recommended Ubuntu setup and let it finish. Completion means the
   app has executed `bash --version`, `ssh -V`, and `tmux -V` inside proot.
3. Open **Connect**, import the user's SSH private key through the Android file
   picker, and choose **Agent droplet**.
4. Confirm the host-key prompt, reach `agent.ght.network`, start an agent CLI,
   background Pocket Shell, lock the phone, and return to the same live CLI.
5. Disable/re-enable networking, reconnect, and confirm `tmux new-session -A
   -s agents` returns to the running agent rather than starting over.
6. Verify ESC, TAB, Ctrl-C, Ctrl-D, arrows, copy/paste, selection, and IME resize.

## Failure history

- v0.2 provisioned required and optional packages in one transaction, swallowed
  the failure, and still wrote the installed marker. One unavailable optional
  package could therefore leave an apparently successful install without SSH.
- v0.2 called `readText()` on provisioning stdout before `waitFor(timeout)`, so
  a hung package manager could block forever and the timeout never ran.
- `PROOT_NO_SECCOMP=1` must not be set. It made the x86_64 emulator's legacy
  `poll`/`fork` path fail with `Function not implemented`.
- Android x86_64 emulator processes run under a zygote seccomp filter that can
  reject legacy syscalls used by the guest. Use it for UI/install-flow checks;
  the required Linux/SSH acceptance target is ARM64 hardware.

## Setup invariants (v0.3+)

- Ubuntu is the compatibility-first default; Alpine remains a lightweight option.
- Bash, OpenSSH, CA certificates, curl, and tmux are mandatory.
- The developer toolkit is best-effort and cannot invalidate working SSH.
- Setup commands have real wall-clock timeouts while stdout drains concurrently.
- Existing pre-v0.3 rootfs installs are repaired in place so `~/.ssh` survives.
- Imported private keys are capped at 64 KiB, format-checked, and written as
  `~/.ssh/id_ed25519` with directory mode 0700 and file mode 0600.
