// swift-tools-version: 6.0
import PackageDescription

// The macOS half of agterm-remote. A menu-bar item that shows the pairing code, says whether the
// configured address actually answers, and owns the bridge process's lifecycle. Nothing else: no
// terminal, no control socket of its own, no place to type a command. The phone is the terminal, and
// this is the thing that lets the phone find the Mac and prove that it may.
//
// The library target exists so the parts worth testing are testable without a Mac app: the address,
// its store, the status model and the menu are ordinary values over an injectable seam, and
// `swift test` runs them with no window, no menu bar and no signing.
let package = Package(
    name: "AgtermRemote",
    platforms: [.macOS(.v14)],
    targets: [
        .target(name: "AgtermRemoteCore"),
        // The menu-bar shell. AppKit, no window, no Dock icon - see Sources/AgtermRemote/main.swift.
        // Its Info.plist is bundled at release time; LSUIElement is declared there AND applied
        // programmatically, so `swift run` behaves like the shipped app.
        .executableTarget(
            name: "AgtermRemote",
            dependencies: ["AgtermRemoteCore"],
            exclude: ["Info.plist"],
        ),
        // Writes the .iconset the app bundle's icon is built from. Separate from the app because it
        // runs at BUILD time and ships nothing: `bundle.sh` runs it, `iconutil` turns its output into
        // an .icns, and the icns goes into Contents/Resources. It exists at all so the icon is
        // derived from Mark.svg rather than drawn a second time by hand.
        .executableTarget(name: "AgtermRemoteIcon", dependencies: ["AgtermRemoteCore"]),
        .testTarget(name: "AgtermRemoteCoreTests", dependencies: ["AgtermRemoteCore"]),
    ],
)
