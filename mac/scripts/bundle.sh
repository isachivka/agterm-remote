#!/bin/bash
#
# Assemble AgtermRemote.app around the binary swift build already produces.
#
# # Why this exists at all
#
# SwiftPM builds a bare Mach-O executable. A menu-bar app needs a BUNDLE, and three things depend on
# that rather than on the binary:
#
#   - `LSUIElement` lives in Info.plist. Without a bundle it is never read, and the only thing keeping
#     the app out of the Dock is the programmatic setActivationPolicy(.accessory) call in main.swift.
#   - `SMAppService.mainApp` - the login item - requires a bundle identity. From a loose executable it
#     cannot work at all.
#   - Double-clicking, and being a thing the owner can find, needs an .app.
#
# Until this script existed the branch produced a binary nobody could launch, which is not a beta.
#
# # What it does NOT do
#
# It does not install, does not copy into /Applications, does not register a login item, and does not
# start anything. Assembling and installing are different acts and the owner authorises them
# separately. The output is a directory in the build tree; moving it anywhere is somebody's decision.
#
# # Signing
#
# Ad-hoc, `codesign -s -`. Neither notarisation nor a Developer ID: this project ships no paid Apple
# identity, and an open-source app that anybody builds from source is not made safer by one anyway -
# what protects the owner here is that the bridge listens on the LAN and hands out nothing without an
# enrolment. Apple Silicon refuses to execute code with no signature at all, so ad-hoc is the floor
# rather than a choice, and the linker's signature on the executable does not cover a bundle.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

CONFIGURATION="${1:-release}"

# The version to stamp into the bundle. EMPTY BY DEFAULT, which leaves Info.plist's own value
# untouched — a hand-build behaves exactly as it did before this argument existed.
#
# # Why it is an argument rather than a value in the file
#
# The plist carries `0.0.0`, and until 2026-08-11 that was what a bundle said about itself no matter
# what release it belonged to. Measured on the runner: an application attached to release 0.11.1
# would have told the owner it was version 0.0.0 in Get Info, in About, and in every crash report,
# sitting beside an .apk that said 0.11.1. Nothing would have failed; it would simply have been
# wrong, in the place people look when they are trying to work out what they are running.
#
# The release passes the same version it gives the .apk, so **one place decides what this release is
# called** and neither half can drift from the other. A second literal in a plist is a second source
# of truth, which is the shape this project keeps removing.
VERSION="${2:-}"

BUILD="$(swift build -c "$CONFIGURATION" --show-bin-path)"
APP="$BUILD/AgtermRemote.app"

swift build -c "$CONFIGURATION"

# Rebuilt from scratch every time. A bundle assembled on top of an older one keeps whatever the last
# build left behind, and "it works here" would then be a statement about files nobody chose.
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"

cp "$BUILD/AgtermRemote" "$APP/Contents/MacOS/AgtermRemote"
cp Sources/AgtermRemote/Info.plist "$APP/Contents/Info.plist"

# BEFORE signing, and that ordering is the whole trick: the signature seals Info.plist, so a version
# written afterwards would produce a bundle whose signature no longer matches its own contents —
# which macOS reports as damaged rather than as unsigned, and which the owner would meet as an app
# that will not open at all.
if [ -n "$VERSION" ]; then
    /usr/bin/plutil -replace CFBundleShortVersionString -string "$VERSION" "$APP/Contents/Info.plist"
    stamped="$(/usr/bin/plutil -extract CFBundleShortVersionString raw "$APP/Contents/Info.plist")"
    # Read back rather than assumed. plutil exits 0 on a key it did not write the way you expected.
    if [ "$stamped" != "$VERSION" ]; then
        echo "refusing to sign: asked for version $VERSION, the plist says $stamped" >&2
        exit 1
    fi
fi

# The icon, and also BEFORE signing: the signature seals Contents/Resources, so an icns added
# afterwards is a bundle whose seal no longer matches itself.
#
# Generated rather than committed. AgtermRemoteIcon renders Mark.svg - the same string the menu bar
# draws - so there is one drawing in this repository, and a change to the mark reaches the icon on
# the next build instead of drifting until somebody notices a menu bar and a Finder icon that no
# longer look like the same product.
mkdir -p "$APP/Contents/Resources"
"$BUILD/AgtermRemoteIcon" "$BUILD/AgtermRemote.iconset"
/usr/bin/iconutil -c icns "$BUILD/AgtermRemote.iconset" -o "$APP/Contents/Resources/AgtermRemote.icns"

# **Declared AND present, checked as one thing.** A bundle that names an icon it does not carry is a
# lie the system tells the owner in the Finder, and it is worse than carrying none - which is the
# state this replaced. Both halves, or the build stops.
declared="$(/usr/bin/plutil -extract CFBundleIconFile raw "$APP/Contents/Info.plist" 2>/dev/null || true)"
if [ -z "$declared" ]; then
    echo "refusing to sign: Info.plist declares no CFBundleIconFile" >&2
    exit 1
fi
if [ ! -s "$APP/Contents/Resources/$declared.icns" ]; then
    echo "refusing to sign: the plist declares '$declared' and Contents/Resources/$declared.icns is missing or empty" >&2
    exit 1
fi

# Ad-hoc, and the whole bundle rather than the executable inside it: Gatekeeper and SMAppService both
# read the bundle's signature, not the linker's.
codesign --force --sign - --timestamp=none "$APP" >/dev/null 2>&1

echo "built    $APP"
echo "         version $(/usr/bin/plutil -extract CFBundleShortVersionString raw "$APP/Contents/Info.plist")"
echo "         $(codesign -dv "$APP" 2>&1 | awk -F= '/^Signature/{print "signature " $2}')"
echo
echo "It is NOT installed, NOT registered at login, and NOT running. To look at it:"
echo "    open \"$APP\""
