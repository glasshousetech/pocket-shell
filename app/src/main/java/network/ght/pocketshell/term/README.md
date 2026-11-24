# Vendored Termux terminal view

These files are the `terminal-view` module of [Termux](https://github.com/termux/termux-app)
at tag **v0.118.0**, copied in verbatim and then modified. Termux is GPLv3; so is Pocket Shell,
and Pocket Shell distributes it under GPLv3. The matching terminal engine and JNI are also
vendored and built from source. See `third_party/termux/README.md` for provenance and changes.

## Why it is vendored rather than depended on

`com.termux.view.TerminalView` is declared `final` and builds its `EditorInfo` — the contract it
hands the soft keyboard — entirely inside `onCreateInputConnection`, with no client hook. There is
no way to influence it from outside: you cannot subclass it, and a parent `ViewGroup` never sees
`onCreateInputConnection`, because the framework calls it on the focused view directly.

## Pocket Shell changes

The local changes are described here and in the repository history.

1. **Package renamed** `com.termux.view` → `network.ght.pocketshell.term` (and `.textselection`),
   so the vendored classes do not collide with the published AAR if it is ever pulled back in.
2. **`TerminalView` is no longer `final`.**
3. **Normal keyboard contract.** Terminal input always declares CLASS_TEXT with NO_SUGGESTIONS,
   no password variation and no NO_PERSONALIZED_LEARNING request. Obsolete saved modes are ignored.
4. **Literal case and Enter.** A single-line IME contract and caps=0 keep SwiftKey from capitalizing
   the first character. Editor actions finish composition and dispatch one terminal Enter.
5. **refreshImeConfiguration().** Restarting input invalidates previous connection callbacks.
6. **Immediate keyboard delivery (2026-11-09).** `ImeConnection` mirrors the keyboard's word in
   progress to the PTY as it changes: each edit sends one DEL per code point after the common
   prefix, then the new remainder, so typing appears at the prompt immediately and corrections
   arrive as DEL keys. Edits inside an IME batch are sent as one difference. Fully committed text
   empties the local editable. A non-keyboard key (Tab, arrows, Esc, Enter, extra keys, hardware
   keys, a one-shot modifier) flushes the word and restarts the keyboard, so a later correction
   cannot reach across it. This replaces the earlier local "Composing:" preview, which held every
   letter until the word was committed and resized the terminal twice per word.
7. **Connection generation.** Stale connections cannot deliver text after another connection,
   tab attachment, detach or input restart. `attachSession()` also restarts input when the view
   has focus; without that the keyboard stayed bound to the previous session's dead connection
   and every keystroke was dropped after a tab switch, new tab or Connect.
8. **Nonblocking input.** Final phrases use the engine's bounded, atomic input batches. A rejected
   IME commit keeps the local draft; reviewed paste reports failure and retains its editor text.
9. **Coalesced accessibility text.** With any accessibility service enabled, the visible screen
   text is refreshed at most every 100 ms and only when it changed, not once per output chunk.

## Updating

Re-copy the upstream files, redo the package rename, then re-apply the numbered changes above.
Diff against `git log --follow` on this directory to see exactly what was touched.
