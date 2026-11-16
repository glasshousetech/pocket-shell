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

## Full-CI preflight corrections

The first full run (43956045396) ran 76 native cases: 72 passed and four failed. Its independent build job failed before compilation because the Android NDK download was not a valid ZIP. The native failures were preserved and corrected before release:

- Tab-routing and product-audit tests assumed both labels fit in the 224 px session viewport on a 320 px phone. The saved hierarchy showed the second tab outside that viewport. Tests now scroll only the named session rail, resolve the real clickable tab, and retain terminal identity, draft, close and reconnect assertions.
- File actions cleared their busy state before asynchronously reloading the durable receipt. A second upload could therefore expose the previous COMPLETE receipt briefly. Receipt readback now remains inside the operation mutex, and busy clears afterwards. The workflow asserts that the second receipt has a new identity and reports CONFLICT before replacement.

A focused run on 320x640 at density 160 passed all seven original regression cases, including real SFTP upload, conflict, retained backup, download and export. A broader final rerun and CI evidence follow in the deployment addendum. The local JVM suite has 229 cases with no failures and one pre-existing optional guest-fixture skip; the native guest checks and signed actual-Linux workflow are separate gates.
