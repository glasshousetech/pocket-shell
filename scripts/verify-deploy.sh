#!/usr/bin/env bash
# Confirms the live download at dl.ght.network actually matches a tagged
# GitHub Release — the exact check that was missing when v0.1.1 shipped a
# fix that never made it past the release page. Run this after every deploy.
#
# Usage: scripts/verify-deploy.sh <tag> [download-url]
# Example: scripts/verify-deploy.sh v0.1.3
set -euo pipefail

TAG="${1:?Usage: verify-deploy.sh <tag> [download-url]}"
URL="${2:-https://dl.ght.network/pocketshell.apk}"
REPO="glasshousetech/pocket-shell"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "==> Downloading release asset for $TAG"
# Current releases use versioned names; historical releases retain their original asset.
version="${TAG#v}"
gh release view "$TAG" --repo "$REPO" --json assets > "$tmp/release.json"
asset="$(python3 - "$tmp/release.json" "$version" <<'PYTHON'
import json, re, sys
assets = {entry["name"] for entry in json.load(open(sys.argv[1]))["assets"]}
expected = "pocketshell-" + sys.argv[2] + ".apk"
if expected in assets:
    print(expected)
elif "app-release.apk" in assets:
    # Filename enforcement starts with 0.7.1; never excuse a new release's regression.
    core = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:[-+].+)?", sys.argv[2])
    if core is None or tuple(map(int, core.groups())) > (0, 7, 0):
        raise SystemExit("Current release is missing its versioned Pocket Shell APK.")
    print("app-release.apk")
else:
    raise SystemExit("No matching Pocket Shell APK in release.")
PYTHON
)"
gh release download "$TAG" --repo "$REPO" -D "$tmp" -p "$asset" --clobber

echo "==> Downloading live file from $URL"
curl -sf "$URL" -o "$tmp/live-pocketshell-${version}.apk"

release_hash="$(sha256sum "$tmp/$asset" | cut -d' ' -f1)"
live_hash="$(sha256sum "$tmp/live-pocketshell-${version}.apk" | cut -d' ' -f1)"

echo "Release ($TAG): $release_hash"
echo "Live    ($URL): $live_hash"

if [ "$release_hash" != "$live_hash" ]; then
  echo "MISMATCH: the live download does not match the $TAG release asset." >&2
  exit 1
fi

echo "OK: live download matches $TAG."
