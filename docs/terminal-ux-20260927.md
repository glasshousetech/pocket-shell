# SSH terminal UX repair — September 27, 2026

## Confirmed causes and changes

- The pinned Termux 0.118.0 `TerminalView.onScreenUpdated()` resets the scroll offset on every output event. `TerminalInteraction.redraw()` anchors the visible history to the same lines as output arrives, clamps at retained history, and leaves selection handling to the engine. Typing or tapping Live returns to the cursor. New sessions retain 20,000 lines instead of 4,000.
- In full-screen applications, swipes belong to the remote terminal protocol. Built-in SSH/tmux connections now enable mouse reporting on the selected tmux session only. History buttons send wheel events to the pane interior, or Shift+PageUp to enter tmux history when mouse tracking is absent. Down uses PageDown in that fallback. Standard PGUP/PGDN keys keep their application meaning. The pinned KeyHandler silently drops paging modifiers; the app now emits xterm modified paging sequences for both hardware and on-screen keys.
- CTRL/ALT previously opened limited menus; Ctrl+B and arbitrary combinations were inaccessible. They now toggle visible, one-shot modifiers; SHIFT is included in presets. Long-press CTRL/ALT retains shortcut menus. Special keys consume modifiers just as characters do, and changing sessions clears modifier state. Custom key orders remain intact. The collapsible extra-key row stays available even when Android reports a hardware keyboard.
- The raw terminal deliberately uses upstream's character input mode, whose password-style EditorInfo can disable IME dictation. Text / voice opens an ordinary Android text editor, supporting keyboard composition, cursor editing and the keyboard's microphone where supplied by the installed IME. Reviewed text is inserted into the originating session without submitting it; line breaks become spaces and control characters are removed. Drafts remain in memory only. The editor consumes system/IME insets and keeps Close/Insert above the field so they remain reachable in landscape. Returning to terminal input waits for window focus and posts the keyboard request after Android has reconnected the IME.
- Connect formerly wrote its SSH command after a fixed 450 ms delay. It now passes the command to the guest login shell at PTY creation, eliminating readiness timing and delayed tab-focus changes.
- Activity recreation previously closed all foreground-service sessions and repeated Linux setup. It now reattaches when live Linux sessions exist, preserving SSH work.

## Validation

Final validation on the isolated ght-dev-server workspace passed: `lintDebug` (zero errors), all 17 unit tests, debug APK and instrumentation APK assembly, seven terminal interaction tests, one full workspace UI test, and one service/PTY smoke test. Evidence is retained in `/home/connor/workspaces/pocketshell-ux-20260927/evidence/`. The review page is https://dl.ght.network/previews/pocketshell-ux-20260927/. The regression suite exercises real PTYs and the pinned terminal engine, including Ctrl+B, Alt+B, Shift+Tab, Ctrl+arrow, composing text, backspace, history anchoring, and remote scroll escape sequences. The UI fixture drives the actual MainActivity using an isolated shell in place of a user's SSH connection. It checks insertion without executing the text, keyboard return with terminal controls visible, preservation through Activity recreation, portrait/landscape layouts at a 360 dp phone width, and an actionable error if the session ends while text entry is open. Gboard displayed a microphone in the ordinary text editor; actual speech recognition was not exercised. This fixture is restricted to emulators.

The tmux command was independently exercised on tmux 3.4 with an isolated socket and a neighboring session. Mouse became enabled only on the intended session. `set-option -t =name` is not accepted by tmux 3.4 even though exact-match target syntax works for several other commands; the generated command uses `-t name`.

## Test environment lessons

Run Gradle and the emulator sequentially on the shared development host. Running them together reduced available memory and caused unreliable system UI behavior. The final run used the owned API 34 Google APIs x86_64 emulator with `-gpu swiftshader -feature -Vulkan -no-snapshot`; boot-time Google service ANRs are environment evidence, not Pocket Shell failures. All final app tests passed after boot, and the owned emulator was stopped afterward. The specific cause of the earlier System UI ANR was not isolated.

The IME regression fixture must attach the TerminalView to a real Activity and wait until InputMethodManager reports it as the served editor before testing deletion. A detached or unfocused view can drop BaseInputConnection's queued backspace events. Production keyboard return must similarly wait for window focus and post its show request after focus dispatch.

## Remaining acceptance

The user's exact startup error text is not yet available. The current owned-phone broker app allowlist does not include Pocket Shell, so the user's phone UI was not accessed. No error stream has been suppressed and these changes do not establish a fix for an unidentified proot, shell-profile, remote-login, or agent-CLI error.

On the user's ARM64 installation, verify the actual keyboard's microphone in Text / voice, touch scrolling in the server's tmux/agent CLI, custom hardware-keyboard shortcuts, SSH reconnect, and new-session startup output. Emulator PTY/UI checks do not establish ARM64 Linux/proot or real speech-recognition success.

The changes were developed from a copy of the existing uncommitted rc.6 checkout; earlier Ubuntu/proot repairs are preserved. The change-specific patch and baseline are retained separately. Connor approved the preview, commit/push, and app update on September 27. Release 0.4.1 uses versionCode 13. The prior installation checks GitHub releases/latest (which excludes prereleases) and compared 0.4.0-rc.6 incorrectly against 0.4.0, so use the newer stable 0.4.1 tag to make this update detectable without changing the installed updater.

## Upstream references

- Terminal view implementation: https://github.com/termux/termux-app/blob/v0.118.0/terminal-view/src/main/java/com/termux/view/TerminalView.java
- Keyboard visibility and window focus: https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility
- Android EditorInfo: https://developer.android.com/reference/android/view/inputmethod/EditorInfo
- tmux mouse and copy-mode behavior: https://github.com/tmux/tmux/wiki/FAQ
