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
# # The bridge rides inside it
#
# The Go bridge is built here, for both architectures, and put in `Contents/Resources`. That is the
# promise the whole macOS design rests on: somebody installs ONE thing. No `go install` first, no
# launchd plist afterwards, and nothing left listening on a port once the app is dragged to the Bin -
# the app spawns the bridge as its own child and it dies with the app.
#
# So this script now needs a Go toolchain, and says so rather than assembling an app whose two menu
# items are permanently grey. `BundledBridge.url(in:)` is the Swift half of the same layout, and
# `BundleLayoutTests` holds it to it.
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

# # The bridge, universal, and BEFORE signing for the third time in this file
#
# Same reason as the version and the icon: the signature seals Contents/Resources, so a binary added
# afterwards produces a bundle macOS calls damaged rather than unsigned.
#
# ## Why both architectures
#
# The person who builds this is not necessarily the person who runs it. A thin arm64 bundle handed to
# somebody on an Intel Mac is an app that opens, shows its menu, and has Start greyed forever with
# nothing on screen to say why - the exact failure this task exists to remove. Two slices cost about
# ten megabytes and remove the whole class.
#
# ## `-trimpath` is not decoration
#
# Without it the Go binary carries the absolute path of every source file it was compiled from -
# `/Users/<whoever>/...`, straight through to whoever downloads the app. That is precisely the class
# of personal value this project refuses to ship, and it is invisible to every guard script in
# `scripts/`, because those read tracked text and this is a build artefact. So it is asserted below,
# against the bytes, rather than trusted to a flag nobody re-reads.
#
# ## CGO_ENABLED=0 on both
#
# Not a cross-compilation workaround - it is already the default for the amd64 half. It is set on the
# arm64 half so the two slices are the same program: cgo would give one of them the system resolver
# and the other Go's own, and it would compile in paths of its own that `-trimpath` does not reach.
BRIDGE="$APP/Contents/Resources/agterm-remote-bridge"

if ! command -v go >/dev/null 2>&1; then
    echo "refusing to build: no Go toolchain, and the bridge ships inside this bundle." >&2
    echo "    Install Go 1.24 or newer and run this again. An app assembled without it would" >&2
    echo "    launch with Start and Stop greyed out and nothing on screen saying why." >&2
    exit 1
fi

slices="$(mktemp -d)"
trap 'rm -rf "$slices"' EXIT

for arch in arm64 amd64; do
    (cd ../bridge && CGO_ENABLED=0 GOOS=darwin GOARCH="$arch" \
        go build -trimpath -o "$slices/bridge-$arch" ./cmd/agterm-remote-bridge)
done
/usr/bin/lipo -create -output "$BRIDGE" "$slices/bridge-arm64" "$slices/bridge-amd64"
chmod 755 "$BRIDGE"

# Ad-hoc, and the binary in its own right. `go build` signs the arm64 slice it produces on this
# machine and leaves the cross-compiled x86_64 one bare, so the joined file is half-signed:
# `codesign -dv` reports adhoc off the arm64 slice while `codesign -v` says "not signed at all".
# Signing it here makes both slices agree, which is what an Intel Mac needs to run it at all.
codesign --force --sign - --timestamp=none "$BRIDGE" >/dev/null 2>&1

# **Both architectures, checked rather than assumed.** `lipo -create` given one input succeeds and
# produces a thin file; a typo in a GOARCH above would ship exactly that, and it would work perfectly
# on the machine that built it.
archs="$(/usr/bin/lipo -archs "$BRIDGE")"
for want in x86_64 arm64; do
    case " $archs " in
        *" $want "*) ;;
        *) echo "refusing to sign: the bridge is '$archs' and needs $want" >&2; exit 1 ;;
    esac
done

# **The build machine's paths, searched for in the shipped bytes.** Measured both ways on 2026-09-09:
# without `-trimpath` this binary carried 25 lines naming the builder's home directory; with it,
# zero. This is the assertion that keeps that true after somebody edits the go build line.
if LC_ALL=C grep -aq '/Users/' "$BRIDGE"; then
    echo "refusing to sign: the bridge carries build-machine paths - is -trimpath still there?" >&2
    LC_ALL=C grep -ao '/Users/[^"]\{0,60\}' "$BRIDGE" | sort -u | head -5 >&2
    exit 1
fi

# Ad-hoc, and the whole bundle rather than the executable inside it: Gatekeeper and SMAppService both
# read the bundle's signature, not the linker's.
codesign --force --sign - --timestamp=none "$APP" >/dev/null 2>&1

echo "built    $APP"
echo "         bridge  $archs, $(/usr/bin/du -h "$BRIDGE" | cut -f1 | tr -d ' '), no build paths"
echo "         version $(/usr/bin/plutil -extract CFBundleShortVersionString raw "$APP/Contents/Info.plist")"
echo "         $(codesign -dv "$APP" 2>&1 | awk -F= '/^Signature/{print "signature " $2}')"
echo
echo "It is NOT installed, NOT registered at login, and NOT running. To look at it:"
echo "    open \"$APP\""
