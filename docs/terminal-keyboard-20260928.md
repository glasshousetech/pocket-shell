# The keyboard's top row in the terminal — September 28, 2026

## Report

On a Galaxy Z Fold 7 with Microsoft SwiftKey, Pocket Shell's terminal showed a keyboard with no
top row: no voice-to-text, no clipboard, no emoji, no keyboard settings. Gboard and Samsung's
keyboard behave the same way. This supersedes the September 27 decision recorded in
`terminal-ux-20260927.md`, which left the raw terminal on upstream's character input mode and
offered the separate Text / voice editor as the place dictation works.

## Cause

The keyboard is doing what it was told. The `EditorInfo` that `TerminalView.onCreateInputConnection`
hands the IME decides what a keyboard shows, and upstream Termux 0.118.0 offers only two shapes:

| `TerminalViewClient` | inputType |
| --- | --- |
| `shouldEnforceCharBasedInput() == true` | `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD \| TYPE_TEXT_FLAG_NO_SUGGESTIONS` |
| `== false` | `TYPE_NULL` |

Both exist for one reason: stop autocorrect mangling commands. The cost was never intended. Every
mainstream keyboard treats a password variation as a sensitive field and drops its whole toolbar
row, and Android blocks voice input on password fields at the platform level. `TYPE_NULL` is worse
for this purpose, because it is not a text field at all.

## Fix

Ask for what was actually wanted — an ordinary text field with suggestions switched off.

```java
outAttrs.inputType = InputType.TYPE_CLASS_TEXT
    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
```

- `NO_SUGGESTIONS` keeps predictive text and autocorrect off, which is the property that mattered.
- `MULTI_LINE` is load-bearing. A single-line text field makes keyboards render an action key
  ("Done") that fires `performEditorAction()` instead of committing a newline, and nothing would
  run at the prompt. With it the keyboard commits `\n`, which `sendTextToTerminal()` already maps
  to the carriage return a shell expects.
- No `CAP_*` flag, so commands are never auto-capitalised.
- `NO_PERSONALIZED_LEARNING` stops a keyboard adding hostnames, paths or a mistyped password to
  its personal dictionary or syncing them to a cloud profile.

## Why terminal-view had to be vendored

`com.termux.view.TerminalView` is `final` and builds its `EditorInfo` entirely inside
`onCreateInputConnection` with no client hook. It cannot be subclassed. A parent `ViewGroup` is no
help either, because the framework calls `onCreateInputConnection` on the focused view directly, so
nothing in the hierarchy ever sees it. A focus-proxy view that owns the IME connection and forwards
key events was the alternative, and it loses the fight for focus on every tap: `TerminalView`
calls `requestFocus()` itself in `onSingleTapUp`.

So terminal-view 0.118.0 now lives in `app/src/main/java/network/ght/pocketshell/term` (Termux is
GPLv3, so is Pocket Shell) and the published dependency narrowed from `terminal-view` to
`terminal-emulator`. The copy is verbatim apart from the package rename; every local change carries
a `Pocket Shell change` comment, and that package's `README.md` records the provenance and the
procedure for pulling a newer upstream.

## Setting

Settings gains a Keyboard section with **Full keyboard features**, on by default. Off restores
upstream char-based input for anyone whose keyboard misbehaves in a text field. Toggling calls
`TerminalView.refreshImeConfiguration()`, which restarts the IME connection so the keyboard already
on screen picks the change up. The Text / voice editor stays as-is; it is still the better surface
for composing or editing a long line before it reaches the shell.

## Regression guard

`TerminalInteractionTest.fullKeyboardModeAsksTheImeForAnOrdinaryTextField` asserts the shape of the
`EditorInfo` in both modes, so this cannot silently revert. Note what an emulator cannot establish:
whether a given third-party keyboard actually renders its toolbar, and whether speech recognition
works. Those need the real handset.
