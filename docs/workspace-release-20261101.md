# Pocket Shell 0.5.4 workspace overhaul

Connor approved publication of the reviewed redesign on November1. VersionCode22
exceeds the public0.5.3/code20 and private review/code21. Existing package ID and
release signing identity are retained.

The native candidate adds per-tab UUID isolation/reconnect, tab management, a
connection library, organized settings, persistent font/power preferences,
adaptive workspace controls, origin-bound AI actions, safer key/removal flows,
retained reviewed drafts and bounded retrievable diagnostics.

Private review evidence:183 JVM tests without skips; final native safety/menu/
adaptive checks and two draft/keyboard/recreation checks passed. Prior unchanged
input/storage paths passed25 checks; all20 themes and direct SSH/GHT hop isolation
and reconnect were exercised. The final lifecycle repair retains ended tabs and
drafts instead of restarting setup. Original failures and repaired harness timing
are retained in the task evidence. Expanded CI now includes the new regressions.

Release gates: CI, exact-version same-signer upgrade from the prior public APK,
signed APK/AAB, downloadable checksum, catalog/updater and GitHub readback.
Successful emulator tests do not establish physical Fold acceptance. The Fold
debugging connection was unavailable at publication preparation; its installed
version, ARM64 setup/repair, valid-key import and real keyboard/network/screen-off
behavior remain unverified. Live AI-provider calls were not made. Multiple keys,
encrypted keys, managed key export and the complete updater handoff remain open.

Updating Android restarts the app and ends local processes. Remote tmux work can
be reattached; historical output is not a resumed local process. Do not silently
install over Connor's active terminal or uninstall it to resolve a signature error.

The first expanded CI run passed41/43 Android checks and exposed a density bug:
an inherited18-pixel minimum made13sp and14sp identical at the CI display density.
The terminal now converts the configured sp size without that unrelated clamp.
The existing font/persistence UI regression remains unchanged. The controlled AI
test now waits for the new user input to be accepted before completing its fake
provider response; all post-response and recreation assertions remain intact.
