# Termux terminal engine source

Upstream: https://github.com/termux/termux-app/tree/v0.118.0/terminal-emulator

The Java engine, native PTY bridge and upstream unit tests are copied from v0.118.0.
The original license is preserved in LICENSE.md. Native code is built with the pinned
Android NDK in app/build.gradle.kts; no JitPack binary or unpublished engine patch is needed.
View provenance remains documented in the vendored terminal-view package.

Pocket Shell changes:

- The outgoing PTY queue accepts bounded, atomic writes without blocking the Android
  main looper. The existing writer thread waits for the subprocess. Incoming output keeps
  a bounded blocking queue and main-thread emulator ownership.
- Incoming output (2026-11-09): the queue is 64 KiB instead of 4 KiB, the reader posts a
  main-thread wakeup only when none is pending, and each wakeup drains everything available
  for up to 8 ms before yielding, with one screen update per drain instead of one per 4 KiB.
- IME text commits batch code-point transformations into one queued write.
- Bracketed paste is queued atomically; overload cannot leave an unmatched paste marker.
- Explicit input rejection is reported to the session client; reviewed drafts can be retained.

When updating upstream, retain these changes and run both the upstream emulator tests
and Pocket Shell's real-PTY input, keyboard, history and lifecycle tests.
