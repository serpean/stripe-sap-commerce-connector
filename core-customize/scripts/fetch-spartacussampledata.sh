#!/bin/sh
# Reference project only: downloads the official Spartacus sample data extension from
# SAP-samples/cloud-commerce-sample-setup into hybris/custom/spartacus/spartacussampledata.
# The extension is a dependency, not part of this repository (it is git-ignored).
# To upgrade, change VERSION and SHA256 to the new release asset.
set -eu

VERSION="221121-x"
SHA256="5d116a15972844c544c21809e2674496780f0ae56854f7f9faa5f763fae14c73"
URL="https://github.com/SAP-samples/cloud-commerce-sample-setup/releases/download/sampledata/spartacussampledata-version-${VERSION}.zip"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/hybris/custom/spartacus/spartacussampledata"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Downloading spartacussampledata ${VERSION}"
curl -fsSL "$URL" -o "$WORK/sampledata.zip"

actual="$(shasum -a 256 "$WORK/sampledata.zip" | cut -d' ' -f1)"
if [ "$actual" != "$SHA256" ]; then
  echo "Checksum mismatch: expected $SHA256, got $actual" >&2
  echo "The release asset changed. Check it, then update VERSION and SHA256." >&2
  exit 1
fi

unzip -q "$WORK/sampledata.zip" -d "$WORK/out"
src="$(find "$WORK/out" -mindepth 1 -maxdepth 1 -type d | head -1)"
rm -rf "$TARGET"
mkdir -p "$(dirname "$TARGET")"
mv "$src" "$TARGET"
echo "Installed $TARGET"
