#!/usr/bin/env bash
# Usage: build-release.sh <version> <tgz-path>
# Builds dist-release/stripe-sap-commerce-connector-<version>.zip and checks it.
# The check prints only file names, never file content.
# Set VERIFY_ONLY_ZIP=<zip> to run only the check on an existing zip.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"

# Paths that must never be in the zip (extended regex, matched on each zip path).
FORBIDDEN_PATHS='(^|/)(stripesampledata|spartacussampledata|recipe|recipes|lib|classes|gensrc|node_modules)(/|$)|(^|/)(\.env[^/]*|\.npmrc)$|(^|/)(extensioninfo|items)\.xsd$|(^|/)hybris/bin/platform(/|$)|(^|/)sample[-_]?storefront(/|$)|(^|/)stripestore(/|$)'
# Content that must never be in a file: Stripe keys and webhook secrets (prefix plus 20 or more key
# characters, so code and docs that only name a prefix or use a placeholder do not match) and npm _auth.
SECRET_PATTERN='(sk|rk)_(live|test)_[A-Za-z0-9]{20,}|whsec_[A-Za-z0-9]{20,}|_auth=[A-Za-z0-9+/=]{8,}'

check_zip() {
	local zip="$1" work paths bad=0 f
	paths="$(unzip -Z1 "$zip")"
	if printf '%s\n' "$paths" | grep -E "$FORBIDDEN_PATHS" > /dev/null; then
		echo "ERROR: forbidden paths in $zip:" >&2
		printf '%s\n' "$paths" | grep -E "$FORBIDDEN_PATHS" >&2
		bad=1
	fi
	work="$(mktemp -d)"
	trap 'rm -rf "$work"' RETURN
	unzip -q "$zip" -d "$work/zip"
	# Open each .tgz too, because the zip compresses it.
	while IFS= read -r -d '' f; do
		mkdir -p "$f.x" && tar -xzf "$f" -C "$f.x"
	done < <(find "$work/zip" -name '*.tgz' -print0)
	while IFS= read -r f; do
		echo "ERROR: possible secret in file: ${f#"$work"/zip/}" >&2
		bad=1
	done < <(grep -rlE "$SECRET_PATTERN" "$work/zip" 2> /dev/null || true)
	return "$bad"
}

if [ -n "${VERIFY_ONLY_ZIP:-}" ]; then
	check_zip "$VERIFY_ONLY_ZIP" && echo "OK: $VERIFY_ONLY_ZIP passed the checks."
	exit $?
fi

if [ $# -ne 2 ]; then
	echo "Usage: $0 <version> <tgz-path>" >&2
	exit 2
fi
version="$1"
tgz="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
[ -f "$tgz" ] || { echo "ERROR: $tgz does not exist." >&2; exit 1; }

cd "$root"
out="$root/dist-release"
stage="$out/stage"
zip_name="stripe-sap-commerce-connector-$version.zip"
rm -rf "$stage" "$out/$zip_name"
mkdir -p "$stage/stripe" "$stage/storefront"

# Only tracked files enter the zip, so nothing ignored can get in.
for ext in stripecore stripefacades stripeocc; do
	base="core-customize/hybris/custom/stripe/$ext"
	git ls-files -z -- "$base" | while IFS= read -r -d '' f; do
		mkdir -p "$stage/stripe/$ext/$(dirname "${f#"$base"/}")"
		cp "$f" "$stage/stripe/$ext/${f#"$base"/}"
	done
done
cp "$tgz" "$stage/storefront/$(basename "$tgz")"
cp README.md CHANGELOG.md LICENSE "$stage/"
git ls-files -z -- docs/migration | while IFS= read -r -d '' f; do
	mkdir -p "$stage/$(dirname "$f")"
	cp "$f" "$stage/$f"
done

(cd "$stage" && find . -type f ! -name SHA256SUMS | sed 's|^\./||' | LC_ALL=C sort | xargs shasum -a 256 > SHA256SUMS)
(cd "$stage" && zip -q -r -X "$out/$zip_name" .)
rm -rf "$stage"

echo "Contents of $zip_name:"
unzip -l "$out/$zip_name" | tail -n 1
check_zip "$out/$zip_name"
echo "OK: $out/$zip_name passed the checks."
