#!/usr/bin/env bash
# Usage: check-version.sh <tag>   (example: v3.0.0)
# Fails when the tag does not match the Spartacus library version or CHANGELOG.md.
# Writes the changelog section of that version to dist-release/RELEASE_NOTES.md.
set -euo pipefail

if [ $# -ne 1 ]; then
	echo "Usage: $0 <tag>" >&2
	exit 2
fi

root="$(cd "$(dirname "$0")/../.." && pwd)"
tag="$1"
version="${tag#v}"
package_json="$root/js-storefront/stripestore/integrations-libs/stripe/package.json"
changelog="$root/CHANGELOG.md"

package_version="$(sed -n 's/^[[:space:]]*"version":[[:space:]]*"\([^"]*\)".*/\1/p' "$package_json" | head -n 1)"
if [ -z "$package_version" ]; then
	echo "ERROR: cannot read the version from $package_json" >&2
	exit 1
fi
if [ "$version" != "$package_version" ]; then
	echo "ERROR: tag '$tag' is version $version, but the library package.json has version $package_version." >&2
	exit 1
fi

if ! grep -qF "## [$version]" "$changelog"; then
	echo "ERROR: CHANGELOG.md has no section '## [$version]'." >&2
	exit 1
fi

mkdir -p "$root/dist-release"
# Print only the section of this version: from its heading to the next '## [' heading.
awk -v heading="## [$version]" '
	index($0, heading) == 1 { found = 1; next }
	found && /^## \[/ { exit }
	found { print }
' "$changelog" | sed -e '/./,$!d' > "$root/dist-release/RELEASE_NOTES.md"

if [ ! -s "$root/dist-release/RELEASE_NOTES.md" ]; then
	echo "ERROR: the CHANGELOG.md section '## [$version]' is empty." >&2
	exit 1
fi
echo "OK: tag $tag matches version $package_version. Release notes: dist-release/RELEASE_NOTES.md"
