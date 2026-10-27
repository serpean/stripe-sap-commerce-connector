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
# characters, so code and docs that only name a prefix or use a placeholder do not match) and npm
# credentials (_auth, _authToken, _password; quoted or not).
SECRET_PATTERN='(sk|rk)_(live|test)_[A-Za-z0-9]{20,}|whsec_[A-Za-z0-9]{20,}|_(auth|authToken|password)[[:space:]]*=[[:space:]]*["'"'"']?[A-Za-z0-9+/=_.-]{8,}'

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
	# Every step checks its own status: the caller may run this function where set -e is off.
	if ! unzip -q "$zip" -d "$work/zip"; then
		echo "ERROR: cannot extract $zip" >&2
		return 1
	fi
	# Open each .tgz too, because the zip compresses it.
	while IFS= read -r -d '' f; do
		if ! { mkdir -p "$f.x" && tar -xzf "$f" -C "$f.x"; }; then
			echo "ERROR: cannot extract ${f#"$work"/zip/}" >&2
			bad=1
		fi
	done < <(find "$work/zip" -name '*.tgz' -print0)
	local matches status
	# grep exits 0 on a match, 1 on no match, 2 on an error: an error is not a clean result.
	matches="$(grep -rlE "$SECRET_PATTERN" "$work/zip" 2> /dev/null)" && status=0 || status=$?
	if [ "$status" -gt 1 ]; then
		echo "ERROR: the secret scan failed (grep status $status)" >&2
		bad=1
	fi
	while IFS= read -r f; do
		[ -n "$f" ] || continue
		echo "ERROR: possible secret in file: ${f#"$work"/zip/}" >&2
		bad=1
	done <<< "$matches"
	return "$bad"
}

if [ -n "${VERIFY_ONLY_ZIP:-}" ]; then
	if check_zip "$VERIFY_ONLY_ZIP"; then
		echo "OK: $VERIFY_ONLY_ZIP passed the checks."
		exit 0
	fi
	exit 1
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

# Only tracked files enter the zip, so nothing ignored can get in. The content is the working tree
# (in CI: the checked-out commit).
for ext in stripecore stripefacades stripeocc; do
	base="core-customize/hybris/custom/stripe/$ext"
	git ls-files -z -- "$base" | while IFS= read -r -d '' f; do
		mkdir -p "$stage/stripe/$ext/$(dirname "${f#"$base"/}")"
		cp "$f" "$stage/stripe/$ext/${f#"$base"/}"
	done
	if [ ! -f "$stage/stripe/$ext/extensioninfo.xml" ]; then
		echo "ERROR: no files of extension $ext were copied (wrong path?)." >&2
		exit 1
	fi
done
cp "$tgz" "$stage/storefront/$(basename "$tgz")"
cp README.md CHANGELOG.md LICENSE "$stage/"
git ls-files -z -- docs/migration | while IFS= read -r -d '' f; do
	mkdir -p "$stage/$(dirname "$f")"
	cp "$f" "$stage/$f"
done

# NUL-separated names, so spaces or quotes in a name do not break the list; the './' prefix is cut from the output.
(cd "$stage" && find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 shasum -a 256 \
	| sed 's|^\([0-9a-f]*  \)\./|\1|' > SHA256SUMS)
(cd "$stage" && zip -q -r -X "$out/$zip_name" .)
rm -rf "$stage"

echo "Contents of $zip_name:"
unzip -l "$out/$zip_name" | tail -n 1
check_zip "$out/$zip_name"
echo "OK: $out/$zip_name passed the checks."
