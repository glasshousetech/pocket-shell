# Pocket Shell 0.7.1: session actions follow the sessions

Connor approved committing, publishing and deploying the preview on November 16, 2026.

Connect and New tab are muted 20 dp icons inside named 48 dp targets, immediately after the last session in the scrolling list. The expanded sidebar places them in a row; the collapsed rail stacks them below numbered tabs. The workspace footer contains Settings only. On phones the two actions follow horizontal tabs, Settings stays pinned and collapsing switches labels to compact numbered tabs. AI Copilot opens from Settings; theme selection remains under Appearance.

Operational entry points record fixed, sanitized events in the existing durable local diagnostic log. Existing session creation, connection behavior, terminal input and preferences are retained.

## Validation before release

- Build/lint and the 229-case unit suite passed on agent-prod (228 passed; one existing optional guest-fixture case skipped).
- 17 unique Android native cases passed, including one/three/thirty-session layouts, scrolling away and back, resizing/collapse persistence, five responsive postures, 1.3 text scaling, 20 themes and selected-tab/reorder/fold regression coverage.
- A real Alpine guest was installed on an owned emulator. New tab created and selected another working Linux session, and its shell executed arithmetic successfully. The synthetic guest was removed after testing.
- Gemini reviewed seven real rendered layouts and found no blockers. Existing hard horizontal scroll clipping and the small gear glyph were minor visual notes.
- An initial host graphics-renderer SIGSEGV and its corrupted synthetic diagnostic log are preserved in the task evidence. The owned emulator was restarted with supported software graphics, the enlarged-text helper was strengthened to require fully visible targets, and both affected cases passed on rerun without weakening product assertions.

## Release proof

Production signing, same-signer upgrade, final signed UI evidence, source/asset hashes and live deployment are recorded in the release evidence and the final deployment addendum. Physical Fold7 installation is separate from emulator and public-release proof. Existing Play API 36 / 16 KB work remains outside this layout change.

The release review tightened the verifier so unversioned assets are accepted only for historical releases through 0.7.0. First-install UI cases temporarily hold and restore synthetic guest markers, and a separate compact-phone overflow case verifies both full touch targets and their actions. Tag and APK version names must match exactly, including RC suffixes, so downloads cannot misrepresent the installed version.

Before publishing, the release workflow reads the built APK's actual versionName/versionCode and rejects mismatches with the tag and source. The signed gate also executes the real Alpine New tab acceptance case after the fresh-install cases. Diagnostic entry-point assertions consider only events recorded after the current test starts. The display-reset convention remains intentional for these explicitly owned, disposable emulator fixtures; no user or shared-device state is tested.

## Full-CI preflight corrections

The first full run (43956045396) ran 76 native cases: 72 passed and four failed. Its independent build job failed before compilation because the Android NDK download was not a valid ZIP. The native failures were preserved and corrected before release:

- Tab-routing and product-audit tests assumed both labels fit in the 224 px session viewport on a 320 px phone. The saved hierarchy showed the second tab outside that viewport. Tests now scroll only the named session rail, resolve the real clickable tab, and retain terminal identity, draft, close and reconnect assertions.
- File actions cleared their busy state before asynchronously reloading the durable receipt. A second upload could therefore expose the previous COMPLETE receipt briefly. Receipt readback now remains inside the operation mutex, and busy clears afterwards. The workflow asserts that the second receipt has a new identity and reports CONFLICT before replacement.

A focused run on 320x640 at density 160 passed all seven original regression cases, including real SFTP upload, conflict, retained backup, download and export. A broader final rerun and CI evidence follow in the deployment addendum. The local JVM suite has 229 cases with no failures and one pre-existing optional guest-fixture skip; the native guest checks and signed actual-Linux workflow are separate gates.

The tab-robot follow-up review approved the bounded helper. An additional local run caught stale close-control accessibility nodes after rotation: the physical screenshot showed Alpha selected, while the cached XML still described Beta's controls. Selection now waits for refreshed active-tab semantics before returning, preserving the existing close-control assertion.

## Production proof: Android 0.7.1

The immutable release source is tag `v0.7.1`, commit `49bcf6c5b9131acac0e9187ad8c12ee07678557b`, versionCode 35. [PR #20](https://github.com/glasshousetech/pocket-shell/pull/20) was merged only after CI 43960628565 passed all 76 native cases; the merged tree matched the reviewed tree exactly. Post-merge CI 43963162898 also passed. The final unit artifact contains 229 cases:228 passed, one optional guest-fixture skip, zero failures/errors. Lint has zero errors and 25 warnings retained in its artifact.

Signed Release 43963205255 passed the 0.7.0 upgrade prepare/verify checks and 11 runtime cases, including real Alpine provisioning and `POCKETSHELL_REAL_LINUX_NEW_TAB_OK`. An independent owned emulator then installed the exact downloaded release asset: both upgrade phases and all 11 runtime cases passed (134.503s). Its installed APK hash equaled the downloaded hash. The production certificate remains `ceee0b987658c2d00bdeec4b61378abb99aee86e05e1d1ee78d4e4d55bdc1420`.

- APK SHA256: `0734184f829504b870d48a0b078b3f778561fe929393647f91da8494f4a46796`.
- AAB SHA256: `3bd0eb54b94d4ad5654a1a32876bf0b6c345310d84556f7521ffa5f3770577cf`.
- [Versioned APK](https://dl.ght.network/apps/pocketshell/0.7.1-0734184f8295/pocketshell-0.7.1.apk), [stable download](https://dl.ght.network/pocketshell.apk), [updater JSON](https://dl.ght.network/pocketshell-latest.json), and [GitHub release](https://github.com/glasshousetech/pocket-shell/releases/tag/v0.7.1) were read back. The public APKs and GitHub asset hashes match. The stable download's actual saved name is `pocketshell-0.7.1.apk`; the unauthenticated GitHub latest endpoint advertises `v0.7.1`.

The existing 0.7.0 app was driven through Settings → Updates on an owned emulator. It offered 0.7.1, and its Download button opened `https://dl.ght.network/pocketshell.apk` in the browser. The native discovery/handoff and public file delivery were verified separately. Current signed layouts were visually checked in expanded, collapsed and compact-phone states.

Website [PR #101](https://github.com/glasshousetech/ght-network/pull/101) is merged at `b898007d42f0d027799f50ef29dee5f2dbb904cc`. Its PR gate passed 645 cases with 100 existing conditional skips, including the Pocket Shell download case in all five postures. Deployment [43965834292](https://github.com/glasshousetech/ght-network/actions/runs/43965834292) completed successfully: the production mobile gate passed 645 cases with 100 conditional skips, and all 48 navigation/motion cases passed. The real public page and version API were then checked with Android emulation at 280x653 and 390x844, plus desktop 1440x900. Both download buttons show 0.7.1 and fit their viewports; their rendered labels were visually reviewed. A real browser click saved `pocketshell-0.7.1.apk` (8,875,592 bytes), SHA256 identical to the signed release above.

Physical Fold7 installation, full TalkBack/keyboard traversal, 2.0 text scaling and the existing Play API 36 / 16 KB work are separate from this bounded Android release and are not claimed as verified. Owned emulators, preview servers, browsers and fixture processes are stopped after evidence capture.
