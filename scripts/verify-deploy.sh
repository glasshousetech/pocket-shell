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
gh release download "$TAG" --repo "$REPO" -D "$tmp" -p "app-release.apk" --clobber

echo "==> Downloading live file from $URL"
curl -sf "$URL" -o "$tmp/live.apk"

release_hash="$(sha256sum "$tmp/app-release.apk" | cut -d' ' -f1)"
live_hash="$(sha256sum "$tmp/live.apk" | cut -d' ' -f1)"

echo "Release ($TAG): $release_hash"
echo "Live    ($URL): $live_hash"

if [ "$release_hash" != "$live_hash" ]; then
  echo "MISMATCH: the live download does not match the $TAG release asset." >&2
  exit 1
fi

echo "OK: live download matches $TAG."
