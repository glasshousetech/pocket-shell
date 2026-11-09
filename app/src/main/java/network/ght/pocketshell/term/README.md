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
6. **Composition preview and deletion.** Provisional speech remains local and visible; corrections
   edit that buffer before any committed-terminal deletion. Final text is committed once in bulk.
7. **Connection generation.** Stale connections cannot deliver text after another connection,
   tab attachment, detach or input restart. Closing a connection discards its provisional text.
8. **Nonblocking input.** Final phrases use the engine's bounded, atomic input batches. A rejected
   IME commit keeps the local draft; reviewed paste reports failure and retains its editor text.

## Updating

Re-copy the upstream files, redo the package rename, then re-apply the numbered changes above.
Diff against `git log --follow` on this directory to see exactly what was touched.
