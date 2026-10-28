#!/usr/bin/env bash
# Run the @UnitTest classes of stripecore, stripefacades and stripeocc with Gradle.
# The SAP platform is not started. It only gives the jars.
# Usage: scripts/dev/unit-tests.sh [--platform <dir>]
set -euo pipefail

platform="${HYBRIS_PLATFORM:-/Volumes/e/y/stripe-ref-platform}"
while [ $# -gt 0 ]; do
  case "$1" in
    --platform) platform="${2:?--platform needs a directory}"; shift 2 ;;
    *) echo "Usage: $0 [--platform <dir>]" >&2; exit 2 ;;
  esac
done

if [ ! -d "$platform/hybris/bin/platform" ]; then
  echo "SAP platform not found in $platform. Use --platform <dir> or set HYBRIS_PLATFORM." >&2
  exit 2
fi

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root/tools/fast-unit-tests"
exec ./gradlew --console=plain -PplatformDir="$platform" test
