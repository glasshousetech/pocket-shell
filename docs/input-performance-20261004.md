# Input performance and public-release readiness

## Task brief

Request: fix stalled/slow voice-to-text input, optimize the app's practical performance,
explain its architecture and improvement opportunities, and prepare for open-source public use.

Baseline: public `glasshousetech/pocket-shell`, master `fc16f3f`, latest stable 0.5.1.
Do not incorporate the separate unmerged tab-menu or artwork branches.

Acceptance criteria:

- Provisional IME composition is visible without sending unconfirmed text to a shell.
- Committed phrases preserve UTF-8, control-key semantics, ordering and exactly-once
  delivery, including when a session is busy, switched, closed or replaced.
- Long input cannot wait for a blocked PTY on the Android main thread.
- Reviewed Text / voice insertion remains session-scoped and never executes a newline.
- Session persistence does not perform full-history scans or disk writes on the input path;
  restore and deliberate exit preserve their intended behavior.
- Instrumentation exercises real PTYs, IME composition, slow readers, Unicode and existing
  terminal controls. Record measured results and limitations rather than claiming recognition
  speed from simulated IME calls.
- Document architecture, privacy, third-party licensing/source obligations, build/release
  procedure, and remaining public-release gates. Preserve existing signing/update identity.

## Investigation

- Full keyboard mode exposes dictation but inherited BaseInputConnection composition has
  no on-screen representation until commit/finish.
- IME commits iterate through every code point and call the terminal writer separately.
- TermService captures full 20,000-row scrollback and writes snapshot JSON synchronously
  on the main looper, even though only 8,000 characters are kept.
- Opt-in transcript logging starts an IO coroutine per redraw and reads a mutable terminal
  screen outside its owning main thread. Network requests are not batched or serialized.
- Even with logging disabled, each redraw recreated encrypted preferences and opened the
  Android keystore to read the flag. A warmed 100-read benchmark took 44,687 ms on the initial
  API 34 Google-image emulator. The cached candidate took under 1 ms.
- The original outgoing 4-KB ByteQueue waits when full. A 62,400-byte UTF-8 phrase committed
  to a deliberately slow PTY reader blocked the main thread for 2,482 ms in that reproduction.
- Bulk delivery removed per-character callbacks; profiling then isolated a costly cold
  normalization loop. The replacement scans a primitive character array and preserves the
  original string unless normalization is necessary.
- Provisional deletion used BaseInputConnection behavior that protects its composing span.
  Dictation corrections now edit the local draft before emitting any terminal deletion.

## Changes

- Build the pinned Termux Java engine/JNI and upstream tests from vendored source, replacing
  the external JitPack dependency. Outgoing input is bounded, ordered, nonblocking and atomic.
- Show provisional composition; reject stale connections after detach, mode change or
  replacement. Keep failed commits/inserts recoverable and preserve terminal modifier semantics.
- Cache encrypted preferences, serialize background snapshot writes with AtomicFile, and
  bound snapshot capture to 128 rows / 8,000 characters. Logging is sampled, bounded and local-only.
- Remove plaintext credential fallback, migrate legacy values only after secure persistence,
  surface save errors and exclude credential-bearing app data from automatic backup/transfer.
- Add architecture, privacy, contribution and security documentation. This repository was
  already public and GPLv3; that does not by itself establish public-release readiness.

## Evidence

In progress: lint and unit tests pass on the isolated development host. API 34 AOSP tests
verify composition, Unicode, modifiers, reviewed paste, stale connections, overload recovery
and the real terminal UI. Warmed 62,400-byte commit: 1 ms, with receiver SHA-256 verification.
Cold-start before/after and hosted CI are still being collected; do not treat the warmed result
as handset dictation latency. An intermittent theme-search UI failure remains under investigation.

Candidate: 0.5.3-rc.1 / versionCode 18, kept separate from the unmerged 0.5.2 tab-menu candidate.
No stable download, updater metadata or physical handset has been changed. Physical keyboard
recognition and the live ARM64 Linux/SSH/tmux workflow remain separate acceptance gates from
the Android input connection and controlled PTY tests. The visual preview awaits approval.
