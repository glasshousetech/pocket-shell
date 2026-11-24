#!/usr/bin/env bash
# One owned CI emulator, four independent continuous clips.
set -euo pipefail
for suite in workbench handoff files jobs; do
  POCKET_DEMO_SUITE="$suite" POCKET_DEMO_OUT="demo-output/$suite" bash scripts/demo/record.sh
  cp "scripts/demo/$suite-story.json" "demo-output/$suite/script.json"
done
