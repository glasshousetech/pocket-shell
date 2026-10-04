# Contributing to Pocket Shell

Pocket Shell is GPLv3. Contributions must preserve third-party notices and include source
for changes to the terminal engine. Never commit signing keys, API keys, private SSH keys,
personal terminal transcripts or local environment files.

## Build and verify

Use JDK 17, Android SDK platform 34 and NDK 27.2.12479018 (pinned by Gradle).
The Gradle wrapper downloads the pinned Gradle version. Native PTY code now builds from
source; JitPack is not required. Disk space is also needed for Android's tools and emulator.

```sh
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=network.ght.pocketshell.InputPerformanceTest,network.ght.pocketshell.TerminalSmokeTest,network.ght.pocketshell.TerminalInteractionTest,network.ght.pocketshell.StorageRegressionTest,network.ght.pocketshell.TerminalWorkspaceUiTest,network.ght.pocketshell.ThemeWorkspaceUiTest
```

Use an isolated emulator. Test fixtures create shell sessions and synthetic application
state; never point them at a real user's active terminal. The upstream terminal-engine
tests run alongside Pocket Shell unit tests. CI runs an explicit instrumentation suite and
uploads UI evidence. Some provisioning tests require a real ARM64 device; emulator success
does not prove that workflow.

Keep the class filter: the separate signed-upgrade fixture requires an old-APK preparation
phase, and ARM64 provisioning fixtures have different prerequisites. For screenshot capture,
use the explicit install/instrument/pull sequence in `.github/workflows/ci.yml`; Gradle's
connected-test task removes the test app after its run.

For input changes, cover Unicode, provisional edits, final commits, exactly-once delivery,
slow readers, closed sessions, tab switches, Ctrl/Alt/Shift, bracketed paste and long history.
For storage changes, cover save/clear ordering, update-in-place and secure-storage failure.
Report measured latency with the device/emulator, payload size and test conditions.

## Releases

Preserve the package ID and signing certificate. Increment versionCode past every previous
published or candidate artifact, including unmerged release branches. The release workflow
builds a signed APK/AAB and stages a draft; it is not proof of a completed public rollout.
Verify the APK metadata, signature, update-in-place, behavioral suite, downloadable hash and
updater version before promotion. Public visual changes require the maintainer's preview approval.

Tagging currently also enables an optional Play internal-track step when credentials are
configured. Play distribution requires a separate platform/policy readiness check; the current
target API 34 build is intended for direct APK distribution.
