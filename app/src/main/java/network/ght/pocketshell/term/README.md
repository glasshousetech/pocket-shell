# Vendored Termux terminal view

These files are the `terminal-view` module of [Termux](https://github.com/termux/termux-app)
at tag **v0.118.0**, copied in verbatim and then modified. Termux is GPLv3; so is Pocket Shell,
so the fork is license-clean. The `terminal-emulator` module is still consumed as a published
artifact (`com.termux.termux-app:terminal-emulator:0.118.0`) — only the *view* is vendored.

## Why it is vendored rather than depended on

`com.termux.view.TerminalView` is declared `final` and builds its `EditorInfo` — the contract it
hands the soft keyboard — entirely inside `onCreateInputConnection`, with no client hook. There is
no way to influence it from outside: you cannot subclass it, and a parent `ViewGroup` never sees
`onCreateInputConnection`, because the framework calls it on the focused view directly.

## Pocket Shell changes

Every change is marked with a `Pocket Shell change` / `Pocket Shell addition` comment.

1. **Package renamed** `com.termux.view` → `network.ght.pocketshell.term` (and `.textselection`),
   so the vendored classes do not collide with the published AAR if it is ever pulled back in.
2. **`TerminalView` is no longer `final`.**
3. **`TerminalViewClient.shouldAllowFullImeFeatures()`** — new client callback. When it returns
   true the terminal declares `TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS` instead of the
   upstream `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` / `TYPE_NULL`. Upstream's choice makes every
   major keyboard (Microsoft SwiftKey, Gboard, Samsung) treat the terminal as a sensitive field
   and drop its whole toolbar row, which is where voice typing, the clipboard and emoji live.
   A plain text field with suggestions off keeps the row and still disables autocorrect.
4. **`IME_FLAG_NO_PERSONALIZED_LEARNING`** is now always set, so a keyboard cannot add anything
   typed at a shell prompt to its personal dictionary or sync it to a cloud profile.
5. **`TerminalView.refreshImeConfiguration()`** — restarts the IME connection so a change to the
   keyboard-mode setting applies to the keyboard already on screen.

## Updating

Re-copy the upstream files, redo the package rename, then re-apply the numbered changes above.
Diff against `git log --follow` on this directory to see exactly what was touched.
