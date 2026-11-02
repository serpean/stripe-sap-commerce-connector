#!/usr/bin/env bash
# Run the @UnitTest classes of stripecore, stripefacades and stripeocc with Gradle.
# The SAP platform is not started. It only gives the jars.
# Usage: scripts/dev/unit-tests.sh [--platform <dir>]
# The platform is --platform, or $HYBRIS_PLATFORM, or the folder ../stripe-ref-platform next to this repo.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
platform="${HYBRIS_PLATFORM:-$root/../stripe-ref-platform}"
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

# A relative path is relative to the folder where the command runs, so make it absolute before the cd.
platform="$(cd "$platform" && pwd)"
cd "$root/tools/fast-unit-tests"
exec ./gradlew --console=plain -PplatformDir="$platform" test
