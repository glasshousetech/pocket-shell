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
- Tests ran on the source before the version bump to rc.10 (metadata only).
- Hosted CI on the pushed revision `47728da` (run 42686728334, API 34): build passed and the full
  native regression suite is **OK (67 tests)**, including the new keyboard regression tests and the
  theme and connection-library tests that failed only on the API 35 image. The `screenshots` job's
  `ScreenshotTest` reported OK (1 test); the hosted emulator then died before `adb logcat`
  ("could not connect to TCP port 5554"), so the job was re-run.
- Signed candidate: GitHub draft release `v0.6.0-rc.10` (run 42686737394). `app-release.apk`
  SHA-256 `3d583e124dc66d892623cc6c784a8e5aee3df1ef5a0192c914246b1705101c7b`, signer
  `ceee0b98…1420` (same key as every prior release, so it upgrades in place), versionCode 32,
  not debuggable. Installed on the API 35 emulator: cold start 355 ms to first-run setup, no crash
  or ANR.
- NOT RUN: physical Fold7 with SwiftKey. An emulator cannot show how SwiftKey reacts to the
  keyboard restart after Tab or arrows mid-word; check that on the handset.

## Follow-up the same day: history on device storage (0.6.0-rc.11)

Connor: Pocket Shell holds everything inside limited app space instead of expanding into device
storage as needed to keep its logs through normal use.

| Finding | Change |
|---|---|
| Restored history was capped (2,000 rows in the snapshot) and scrollback beyond 20,000 rows was simply lost. | Every row that becomes scrollback is appended, exactly once, to `noBackupFiles/history/<tabId>.log` (`SessionHistory`). No line limit; it grows with device storage. The snapshot now holds only the screen rows not yet in that file (at most one screenful), so no history scan runs on the main thread at all. Find in output searches the whole file. |
| Every column change (fold/unfold, font size, attaching a restored tab) made `TerminalBuffer.resize` allocate all 20,000 rows of every tab up front (~25 MB per tab at Fold widths), then garbage-collect the old ones. | Rows are allocated as they are used; a rebuild only creates rows that hold content. |
| The opt-in debug transcript truncated itself at 512 KB and its queue dropped entries under load. | No size cap; unbounded queue. |

How "exactly once" holds: `TerminalBuffer` emits the row that leaves the screen on each scroll.
Keyboard resizes move rows between the screen and scrollback without scrolling, so a counter of
"top screen rows already logged" is kept: a shrink logs the rows it pushes up (unless already
logged), an expand marks returning rows as logged, and any write to such a row clears the mark.
A column reflow records where unlogged content starts and, if the rebuild pushes it into
scrollback, logs it then. The alternate screen (tmux, vim, htop) is never logged; tmux keeps its
own history. JVM tests in `HistoryListenerTest` cover each case.

History is deleted with its tab, on Clear Scrollback, and on Exit, matching restored-tab lifetime.
After a process kill the newest `RESTORED_SCROLLBACK_LINES` (5,000) lines are replayed into live
scrollback; replaying a long file would stall cold start, and the rest stays searchable.

### Evidence for 0.6.0 (agent-prod, isolated API 35 emulator)

- JVM: 226 tests, 0 failures (1 pre-existing skip), including `HistoryListenerTest` (8), history
  tail tests (3), ENTER layout tests, and all 13 upstream `ResizeTest` cases on on-demand rows.
- `WorkspaceControlsUiTest`: OK (3). ENTER on the special-keys row submits the typed command;
  the menu collapses on the phone layout and on a 1600x1800/320dpi foldable layout and stays
  collapsed after recreation; a 25,000-line session keeps rows 1 and 25,000 on device (row 1 no
  longer in memory), each row exactly once, and closing the tab deletes the file.
- Input, output and persistence suites: OK (32). UI suites: OK (29). Theme and connection-library
  suites were run on hosted CI (API 34) only; on this API 35 image they fail before this change.

## Deployment — 0.6.0, November 9, 2026

Connor: "Do all the fixes first, deploy it."

- Tag `v0.6.0` at `d1684c0`; signed release run 42710384573 passed, including the same-signer
  upgrade from the public 0.5.4 APK (prepare/verify) and the signed runtime smoke (6 tests).
- APK SHA-256 `473ea7b3a4619cf09eab988cadc27575e4e1e835904b099ee3b5d2fc69d266ae`, signer
  `ceee0b98…1420`, versionCode 33, not debuggable.
- Live: `https://dl.ght.network/pocketshell.apk` (stable), immutable
  `https://dl.ght.network/apps/pocketshell/0.6.0-473ea7b3a461/pocketshell-0.6.0.apk` (exact path
  added to the Caddy download allow-list), `pocketshell-latest.json` (versionCode 33).
  `scripts/verify-deploy.sh v0.6.0`: OK. GitHub `releases/latest` is v0.6.0, so the in-app
  updater offers it.
- Rollback: `/opt/halo-dl/pocketshell.apk.bak-0.5.4-*`, `pocketshell-latest.json.bak-0.5.4-*` and
  `/etc/caddy/Caddyfile.bak-pocketshell-0.6.0-*` on gh-cloud-01; the 0.5.4 immutable URL still serves.
- Website: ght-network PR #96 removed Pocket Shell from the download hold and lists 0.6.0.
- Not done: the Fold7 itself was not updated remotely (installing ends running sessions). Update
  from Settings → Check for updates, or the download link.
