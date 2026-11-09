# Keyboard responsiveness and lag — November 9, 2026

## Report

Connor, on the Fold7 with 0.6.0-rc.9: Pocket Shell still lags, and text input from the keyboard
"is not being responsive at all". Request: a full workup of every performance issue, then fix them.

Baseline: `codex/fold-emulator-20261107` at `eda9b76` (0.6.0-rc.9), the newest code on any branch.

## Root causes, in the order a user meets them

| # | Symptom | Cause | Fix |
|---|---|---|---|
| 1 | After switching tabs, opening a tab or Connect, typing does nothing until something steals focus | `TerminalView.attachSession()` bumps the IME connection generation, which makes the old connection reject all input by design, but never calls `InputMethodManager.restartInput()`. The keyboard stays bound to the dead connection. | `attachSession()` restarts input when the view has focus. |
| 2 | Letters do not appear at the prompt until the word is finished; Tab completion and single-key prompts miss the letters being typed | Since the normal-keyboard contract (rc.9), SwiftKey and dictation compose whole words. The connection held composing text locally ("Composing: …" preview) and sent nothing until commit. Extra keys (Tab, arrows, Ctrl) therefore reached the shell *before* the word typed ahead of them. | The connection mirrors the keyboard's word to the PTY on every change: DEL for each code point after the common prefix, then the new remainder. One difference per IME batch. A non-keyboard key flushes and restarts the keyboard, so corrections cannot reach across it. |
| 3 | The terminal jumps and repaints while typing; tmux redraws the whole screen | The "Composing:" bar appeared on the first letter of every word and disappeared on commit. The terminal's height changed twice per word, so the PTY got two `SIGWINCH`s per word (tmux and full-screen apps repaint on each). Reading `composingText` in `PocketShellApp` also recomposed the entire app on every keystroke. | The preview bar and its state are removed; typed text is visible in the terminal itself. |
| 4 | A stall every 20 s and on every tab create/close/rename/move | Regression from `9256ed9` (Oct 31): `persistSnapshot()` went back to `screen.transcriptText`, copying all 20,000 scrollback rows of every tab on the main thread, then serializing megabytes of JSON and re-parsing the previous file before each write. 0.5.3 had already bounded this. | Capture a bounded tail (`PERSISTED_HISTORY_ROWS` = 2,000 rows) only for tabs whose screen changed since the last capture; skip writing an identical snapshot; check an existing file's version once per process. Restore replays at most the same number of rows, so old 20,000-row snapshots no longer stall cold start. |
| 5 | Typing stutters while a command prints a lot | The PTY reader posted one main-thread message per read, each appending at most 4 KiB and triggering a full screen update and redraw callback. | 64 KiB output queue; at most one pending wakeup; each wakeup drains everything for up to 8 ms, then yields; one screen update per drain. |
| 6 | Extra lag on phones with any accessibility service enabled (password managers, automation, Samsung services) | Every output chunk rebuilt the visible screen text and set it as the content description, which sends an accessibility event each time. | Refreshed at most every 100 ms, and only when the text changed. |

Not causes: PRoot keeps the seccomp fast path (`PROOT_NO_SECCOMP` is not set) and the custom
native patch changes only the x86_64 binary; the Fold runs the unchanged ARM build. The input
queue, encrypted-preference caching and transcript-logger sampling from 0.5.3 were already fine.

## Behavior change to know about

Uncommitted keyboard text, including dictation in progress, now reaches the shell as it is typed,
exactly like typing into any terminal. Keyboard corrections arrive as backspaces. Dictation that
must be reviewed before the shell sees it uses **Text / voice**, which is unchanged and never
sends a newline. This reverses the October 4 criterion "provisional composition is visible without
sending unconfirmed text to a shell", because that criterion is what made typing feel dead.

## Limits kept, with reasons

- `PERSISTED_HISTORY_ROWS = 2_000`: history capture reads the main-thread-owned emulator; this
  bounds a periodic main-thread scan. Raise it if more restored history is worth the cost.
- `OUTPUT_DRAIN_BUDGET_NANOS = 8 ms`: output is still fully drained, only split across message-loop
  turns so touch, keyboard and drawing are not starved.
- Accessibility refresh interval 100 ms: Android already throttles content-change events to this.

## Verification

See the evidence section appended below for the exact runs on the final revision.

## Evidence — November 9, 2026

Built on agent-prod (`ght-agents`), isolated AVD `claude-perf-api35` (API 35 AOSP x86_64, stock
LatinIME, animations off), own adb server port; no other agent's emulator was touched.

- JVM unit tests: 216 run, 0 failures, 1 pre-existing skip (includes new `TerminalSnapshotTest`).
- Input, output and persistence instrumentation (`TerminalInteractionTest`, `InputPerformanceTest`,
  `TerminalOutputDrainTest`, `TerminalSmokeTest`, `StorageRegressionTest`,
  `RemoteWorkspacePersistenceTest`): **OK (32 tests)**. 62,400-byte dictated phrase to a slow
  reader: main-thread commit 1 ms, exact UTF-8 received.
- New regression tests run against the unmodified rc.9 build fail as expected, proving they detect
  the defects: `typedLettersReachTheShellBeforeTheKeyboardCommitsTheWord` ("Expected 6c 73",
  shell saw nothing), `attachingAnotherSessionRebindsTheKeyboard` ("must bind a fresh keyboard
  connection"), `tabMidWordSendsTheWordFirstAndStartsAFreshKeyboardWord`.
- UI suites (16 classes, 32 tests): 30 pass. `ThemeWorkspaceUiTest.allThemesApplyPersistAndKeepLiveSessions`
  and `ConnectionLibraryTest.createFavoriteSearchReloadAndRemoveUsingTheRealUi` fail identically on
  the unmodified rc.9 build on the same API 35 emulator, so they are pre-existing on that image and
  not caused by this change; hosted CI runs them on API 34.
- Tests ran on the source before the version bump to rc.10 (metadata only). Hosted CI runs the
  full suite on the pushed revision.
- NOT RUN: physical Fold7 with SwiftKey. An emulator cannot show how SwiftKey reacts to the
  keyboard restart after Tab or arrows mid-word; check that on the handset.
