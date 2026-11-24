# Signed release retry

The emulator runner starts each script line with a separate `sh -c`. A shell
variable assigned on one line does not reach the next. The first0.5.4 release
build signed successfully but stopped before instrumentation at an empty-version
check. The workflow now stores the derived version in a checked file and reads
it within each instrumentation command. Exact-version assertions are retained.

Manual retries require an existing tag, check out that immutable tag and record
the actual build commit. The release action targets that tag explicitly. This
allows repairing the runner without moving an already-pushed version tag or
mistaking the workflow commit for the app build commit. Source/version receipts
are uploaded alongside both upgrade phases. Independent-shell local validation
read back22; the real signed upgrade remains the decisive release gate.

Reference: https://github.com/ReactiveCircus/android-emulator-runner/blob/main/src/main.ts

The workflow-only CI repeat passed42/43 but hit the same stale accessibility
cache on the AI result read; the earlier correction refreshed only input reads.
Apply the same fresh-node helper to result reads before/after recreation, and
assert provider completion in retained state separately. Failure captures now
cover every fresh-text lookup. No app-source or assertion requirement changed.
