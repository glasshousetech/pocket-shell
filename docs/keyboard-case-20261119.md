# Keyboard capitalization on space — 0.7.2

## Report

Typing a command and pressing space capitalized its first letter, so the shell ran `Claude`
instead of `claude` and failed. Reported on the Galaxy Z Fold 7 with Microsoft SwiftKey.

## Cause

The terminal already asks every keyboard for literal text: `TYPE_CLASS_TEXT` with
`TYPE_TEXT_FLAG_NO_SUGGESTIONS`, no `CAP_*` flag, `initialCapsMode = 0` and
`getCursorCapsMode() = 0`. SwiftKey 9.13 ignores `NO_SUGGESTIONS`: its prediction bar stays
on, and when space is pressed it commits its dictionary spelling of the word it was composing.
For proper nouns that spelling is capitalized. The keyboard bridge (`TerminalView.ImeConnection`)
mirrors every keyboard edit to the PTY, so it faithfully sent the "correction" as deletes plus the
new word.

Logged call sequence for `claude` + space on the unfixed build (real SwiftKey, test emulator):

```
setComposingText [c] … [claude]        -> PTY: c l a u d e
commitText [Claude] (replaces "claude") -> PTY: DEL ×6, C l a u d e
commitText [ ]                          -> PTY: space
```

The earlier rc.8 check only typed one letter, so it proved the first keypress stays lowercase
but never pressed space. That is why this survived the November 9 fix.

## Change

A keyboard may still add text, delete text, and complete a word, but it may no longer change
the case of letters already typed.

- `TypedCase.keep(typed, proposed)` puts back the typed case on the leading run of characters
  that differ only by case. It compares by code point, and everything after the first real
  difference is left to the keyboard.
- Terminal: `ImeConnection.sync()` applies it before diffing against what the PTY already has.
- Text / voice editor: `CommandEditText` applies it when the keyboard replaces its composing word
  (`setComposingText`, `commitText`). Replacing a selection is the user's own edit and keeps its case.
- Diagnostics: one content-free `input/keyboard_case_kept` event per keyboard connection when a
  re-case was refused. Typed text is never logged.

## What the contract experiments ruled out

Real SwiftKey on the Fold-size API 37 emulator, unfixed build, `EditorInfo.inputType` varied:

| Contract | `claude␠` → | Toolbar (mic, clipboard) | Layout |
|---|---|---|---|
| text + NO_SUGGESTIONS (current) | `Claude` | kept | unchanged |
| + VARIATION_FILTER | `Claude` (4 of 4 runs, alternated A/B/A/B) | kept | unchanged |
| + VARIATION_URI | autocorrects | kept | unchanged |
| + VARIATION_EMAIL_ADDRESS | not measured on proper nouns | kept | adds `@`, narrower space bar |
| + VARIATION_WEB_EDIT_TEXT | `Ls`, `Echo` (auto-shift) | kept | unchanged |
| VARIATION_VISIBLE_PASSWORD | literal | **hidden**, number row instead | changed |

Only the password contract makes SwiftKey literal, and it removes the toolbar, which was
explicitly kept in September (`terminal-keyboard-20260928.md`). So the fix lives in the bridge,
not in the contract.

SwiftKey's autocorrect is adaptive: early runs also turned `ssh` into `ash` and `npm` into `no`,
then stopped once those words were learned. Proper-noun capitalization did not fade (`Claude`
and `Linux` on every unfixed run). SwiftKey's separate "auto space" setting also turns `a.txt`
into `a. txt` in every non-password contract. Both are whole-word changes rather than case
changes, so this release does not touch them.

## Verification

- Unit: `TypedCaseTest` (5 tests); full JVM suite 234/234.
- Instrumented, API 34 like CI: `TerminalInteractionTest.keyboardCannotRecapitalizeWordsAlreadyInTheShell`
  and `CommandEditorTest.keyboardCannotRecapitalizeTheWordItCommits` pass. Both fail on master
  (`Ls ` / missing bytes). IME classes: 29 pass, 2 skipped (SwiftKey probes, stock keyboard).
- Real SwiftKey 9.13.19.3, `SwiftKeyCaseProbe` with the same keystrokes:
  - master: terminal got `claude⌫⌫⌫⌫⌫⌫Claude ⏎ linux⌫⌫⌫⌫⌫Linux ⏎`; editor held `Claude Linux grok `.
  - fix: terminal got `claude ⏎ linux ⏎ grok ⏎ ls ⏎`; editor held `claude linux grok `.

### Running the SwiftKey probe

It needs an emulator with SwiftKey as the active keyboard (APK splits: base + arm64 + en +
xxhdpi; the API 37 google_apis x86_64 image translates arm64). Key centres for a 1968×2184,
420 dpi Fold-size AVD in SwiftKey's split layout:

```
q=86,1532;w=258,1532;e=429,1532;r=601,1532;t=773,1532;y=1192,1532;u=1365,1532;i=1538,1532;o=1709,1532;p=1880,1532;
a=171,1673;s=344,1673;d=515,1673;f=688,1673;g=860,1673;h=1278,1673;j=1451,1673;k=1623,1673;l=1794,1673;
z=344,1816;x=515,1816;c=688,1816;v=860,1816;b=1278,1816;n=1451,1816;m=1623,1816;space=1026,1958;enter=1838,1958;.=1623,1958
```

```
adb shell "am instrument -w -e class network.ght.pocketshell.SwiftKeyCaseProbe -e keys '<above, one line>' -e text 'claude linux grok ' network.ght.pocketshell.test/androidx.test.runner.AndroidJUnitRunner"
```

Use words SwiftKey treats as proper nouns. It learns ordinary words within a run or two and
then stops correcting them.

Still open: handset confirmation on the Fold 7 after it updates. Emulator SwiftKey is not
Samsung firmware, and the phone's SwiftKey has its own learned dictionary.
