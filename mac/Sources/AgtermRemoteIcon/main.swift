import AgtermRemoteCore
import AppKit

// Writes the .iconset that `iconutil` turns into AgtermRemote.app's icon.
//
// It exists as a separate executable rather than as a step inside `bundle.sh` because the drawing is
// Swift: the icon is derived from `Mark.svg`, the same string the menu bar renders, so there is one
// drawing in this repository and not two. A shell script cannot read that string; this can.
//
// It writes and nothing else — no signing, no bundle, no install. `bundle.sh` runs it, calls
// `iconutil`, and puts the result where the plist says.
//
// Usage: AgtermRemoteIcon <output.iconset directory>

let arguments = CommandLine.arguments
guard arguments.count == 2 else {
    FileHandle.standardError.write(Data("usage: AgtermRemoteIcon <output.iconset>\n".utf8))
    exit(2)
}

let directory = URL(fileURLWithPath: arguments[1])
do {
    // Rebuilt from scratch. A set assembled on top of an older one keeps whatever the last run left
    // behind, and iconutil would happily build an icns from a size nobody generated today.
    try? FileManager.default.removeItem(at: directory)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

    for file in AppIcon.iconsetFiles {
        guard let png = AppIcon.png(points: file.points) else {
            FileHandle.standardError.write(Data("could not render \(file.name)\n".utf8))
            exit(1)
        }
        try png.write(to: directory.appendingPathComponent(file.name))
    }
} catch {
    FileHandle.standardError.write(Data("writing the iconset failed: \(error)\n".utf8))
    exit(1)
}

print("wrote \(AppIcon.iconsetFiles.count) files to \(directory.path)")
