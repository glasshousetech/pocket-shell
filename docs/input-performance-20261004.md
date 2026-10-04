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
  Recovery preserves an unread key together with its provider address/model and retains
  conflicting legacy values until the user can review them.
- Add architecture, privacy, contribution and security documentation. This repository was
  already public and GPLv3; that does not by itself establish public-release readiness.

## Measured results

Same development host, API 34 AOSP x86_64 image, 2 vCPUs / 2 GB emulator RAM. The test runs
first in a fresh instrumentation process. A controlled raw PTY waits two seconds before reading
a 62,400-byte phrase containing accented text, Japanese and emoji; the receiver verifies SHA-256.

| Measurement | Baseline `fc16f3f` | Candidate production source `4a7033f` |
|---|---:|---:|
| Main-thread final-phrase commit, cold | 2,304 ms | 100 ms |
| 100 warmed disabled-transcript flag checks | 5,686 ms | Under 1 ms |
| Exact UTF-8 received | Yes, after blocking | Yes, without waiting for the slow reader |

The first candidate measured 19 ms cold on the independent hosted runner, and 1 ms in a
warmed development-host run. Final production source `252d950` (0.5.3-rc.2) measured 17 ms
cold on the hosted runner with exact UTF-8 delivery; 100 warmed flag checks remained under
1 ms. Different hosts and warm-up states are not interchangeable.
These numbers measure **app input handling**, not microphone-to-text recognition, network
latency, PRoot overhead or physical-handset performance. No claim of a 23x faster recognizer.

## Verification

- [Hosted CI for the final production source](https://github.com/glasshousetech/pocket-shell/actions/runs/37190070045):
  lint passes (0 errors, 24 warnings), 172 unit tests pass with no skips/failures, and all 24
  instrumentation tests execute and pass. Artifacts contain timing logs, unit reports and UI images.
- The real-PTY suite covers provisional edits, Unicode, Ctrl/Alt/Shift, Enter, bracketed paste,
  exactly-once composition, stale connections, keyboard-mode changes, queue overload/recovery,
  scrollback, alternate-screen/mouse behavior, activity recreation and ended-session handling.
- Storage tests cover ordered save/clear, unavailable encrypted storage, blank/missing legacy
  migration, conflicting legacy values and preserving an unread key with its original
  endpoint/model during recovery. An intentional subsequent blank save still clears it.
- Workspace UI checks cover the reviewed editor and keyboard controls in portrait/landscape;
  the theme test applies all 20 themes and checks a narrow phone and wider foldable layout.
- [Signed release build and upgrade test](https://github.com/glasshousetech/pocket-shell/actions/runs/37190222053):
  install current public APK, prepare synthetic theme/private-file state, update in place,
  then verify retained state. Both preparation and verification pass. Play upload was skipped.
- Downloaded APK independently verifies with apksigner. Package `network.ght.pocketshell`,
  version `0.5.3-rc.2`, code `19`, min SDK 26, target SDK 34. Its certificate SHA-256 matches
  0.5.1: `ceee0b987658c2d00bdeec4b61378abb99aee86e05e1d1ee78d4e4d55bdc1420`.
- APK SHA-256: `e48e879a14db5cf9af2ca869a1ba6aea9cde45e82107fbd85fc97c1140d6913d`.
- Release tag `v0.5.3-rc.2` points to production source `252d950a25efc8e825b8e81fd66ab8fff164e299`.
  Later evidence/architecture changes are documentation-only. The earlier 0.5.3-rc.1 draft
  is superseded; do not install it instead of rc.2.
- `scripts/verify-deploy.sh v0.5.1` confirms the unchanged stable download matches its GitHub
  asset: `ab692a671fb6d81a0d04e7906bfc9301b33a3f067367782470cbef0c02e4aaa3`.

## Test-environment limitations and open gates

- Development-host UI runs intermittently hit **Android System UI isn't responding**;
  captured UI hierarchy confirms the system dialog. Those runs are not passing app evidence.
  Earlier local theme-search timing failures were not fully diagnosed, although complete
  hosted runs passed. Keep this as a handset/regression-validation item, not a closed defect.
- Accessibility can expose preview nodes before the compositor presents the frame. A test-only
  follow-up waits for idle and two animation frames before capturing the preview. The final
  production-source CI includes this wait; its portrait and landscape captures were inspected.
- No reachable authorized phone: the saved SSH bridge refused connection; laptop ADB listed no
  device, and the two known phone network endpoints timed out/refused. No phone settings,
  permissions, app installation or active personal terminal was changed.
- Physical keyboard recognition and ARM64 Ubuntu/SSH/tmux, lock/unlock and network interruption
  acceptance remain required by `docs/RUNTIME.md`. Simulated IME/Android PTY tests do not replace them.
- The new composition-strip preview awaits maintainer approval before public visual promotion.
- API 36/Play migration, 16-KB native compatibility, shared-storage policy, wake-lock behavior
  and complete corresponding-source provenance for the bundled PRoot binaries remain public-launch work.

## Handoff

Candidate `0.5.3-rc.2` is a signed **draft prerelease**, not a public/stable rollout. It is kept separate from
the unmerged 0.5.2 tab-menu candidate. [Change review](https://github.com/glasshousetech/pocket-shell/pull/14).
The stable download and updater remain on 0.5.1. No claim that the installed handset is fixed yet.
Architecture and prioritized improvements: [ARCHITECTURE.md](ARCHITECTURE.md).
