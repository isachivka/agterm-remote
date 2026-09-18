#!/bin/bash
#
# Wrap the assembled AgtermRemote.app in a disk image somebody can download and drag.
#
# # Why a .dmg and not a .zip
#
# A zip of an .app expands wherever the browser put it, and the app then runs from ~/Downloads with
# no hint that it was meant to live anywhere else. The predecessor project shipped that way and the
# consequence was a bundle nobody could find again. A disk image opens onto a window with the app and
# a symlink to /Applications side by side, which is the only install instruction that needs no words.
#
# # What it does NOT do
#
# It does not build the app - `bundle.sh` does, and this refuses rather than guessing if that has not
# been run. It does not sign, notarise or staple: the bundle is ad-hoc signed and this repository
# ships no paid Apple identity, so the disk image inherits exactly that and the README says what
# Gatekeeper will show. It does not install anything.
set -euo pipefail

CONFIGURATION="${CONFIGURATION:-release}"
BUILD="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && swift build -c "$CONFIGURATION" --show-bin-path)"
APP="$BUILD/AgtermRemote.app"
VERSION="${VERSION:-0.0.0}"
OUT="${OUT:-$BUILD/AgtermRemote-$VERSION.dmg}"

if [ ! -d "$APP" ]; then
    echo "error: $APP does not exist." >&2
    echo "    Run mac/scripts/bundle.sh first. This script packages an app; it does not build one," >&2
    echo "    and an empty disk image that uploads cleanly is worse than a failure here." >&2
    exit 1
fi

# A staging directory rather than the build tree, so the image contains the app and the symlink and
# nothing else the build happened to leave lying next to it.
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"

rm -f "$OUT"
hdiutil create \
    -volname "AgtermRemote" \
    -srcfolder "$STAGE" \
    -ov \
    -format UDZO \
    "$OUT" >/dev/null

# Ejecting races whatever indexes a freshly mounted volume, and "Resource busy" is the normal
# outcome rather than an error worth failing a release over - the image is already written and
# already verified by then. Retry, then force.
detach() {
    for _ in 1 2 3 4 5; do
        if hdiutil detach "$1" >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
    done
    hdiutil detach "$1" -force >/dev/null 2>&1 || true
}

# Proof, not hope: the image must mount and the app must be inside it. `hdiutil create` succeeding
# says a file was written, which is not the same claim.
MOUNT="$(mktemp -d)"
hdiutil attach "$OUT" -mountpoint "$MOUNT" -nobrowse -readonly >/dev/null
if [ ! -d "$MOUNT/AgtermRemote.app" ]; then
    detach "$MOUNT" || true
    echo "error: the disk image mounted without AgtermRemote.app inside it." >&2
    exit 1
fi
detach "$MOUNT"
rmdir "$MOUNT" 2>/dev/null || true

echo "$OUT"
