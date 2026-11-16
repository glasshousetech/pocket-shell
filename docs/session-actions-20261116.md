# Pocket Shell 0.7.1: session actions follow the sessions

Connor approved committing, publishing and deploying the preview on November 16, 2026.

Connect and New tab are muted 20 dp icons inside named 48 dp targets, immediately after the last session in the scrolling list. The expanded sidebar places them in a row; the collapsed rail stacks them below numbered tabs. The workspace footer contains Settings only. On phones the two actions follow horizontal tabs, Settings stays pinned and collapsing switches labels to compact numbered tabs. AI Copilot opens from Settings; theme selection remains under Appearance.

Operational entry points record fixed, sanitized events in the existing durable local diagnostic log. Existing session creation, connection behavior, terminal input and preferences are retained.

## Validation before release

- Build, lint and 229 unit tests passed on agent-prod.
- 17 unique Android native cases passed, including one/three/thirty-session layouts, scrolling away and back, resizing/collapse persistence, five responsive postures, 1.3 text scaling, 20 themes and selected-tab/reorder/fold regression coverage.
- A real Alpine guest was installed on an owned emulator. New tab created and selected another working Linux session, and its shell executed arithmetic successfully. The synthetic guest was removed after testing.
- Gemini reviewed seven real rendered layouts and found no blockers. Existing hard horizontal scroll clipping and the small gear glyph were minor visual notes.
- An initial host graphics-renderer SIGSEGV and its corrupted synthetic diagnostic log are preserved in the task evidence. The owned emulator was restarted with supported software graphics, the enlarged-text helper was strengthened to require fully visible targets, and both affected cases passed on rerun without weakening product assertions.

## Release proof

Production signing, same-signer upgrade, final signed UI evidence, source/asset hashes and live deployment are recorded in the release evidence and the final deployment addendum. Physical Fold7 installation is separate from emulator and public-release proof. Existing Play API36/16KB work remains outside this layout change.

The release review tightened the verifier so unversioned assets are accepted only for historical releases through 0.7.0. First-install UI cases temporarily hold and restore synthetic guest markers, and a separate compact-phone overflow case verifies both full touch targets and their actions. Tag and APK version names must match exactly, including RC suffixes, so downloads cannot misrepresent the installed version.

Before publishing, the release workflow reads the built APK's actual versionName/versionCode and rejects mismatches with the tag and source. The signed gate also executes the real Alpine New tab acceptance case after the fresh-install cases. Diagnostic entry-point assertions consider only events recorded after the current test starts. The display-reset convention remains intentional for these explicitly owned, disposable emulator fixtures; no user or shared-device state is tested.
