import AppKit

/// The application icon: the same mark, on a ground, because a template is not an icon.
///
/// ### Why this cannot just be `Mark.image`
///
/// `Mark` is a **template**: one colour plus alpha, tinted by the system, designed to read at 16 and
/// 18 points against a menu bar whose colour it cannot know. Written into an `.icns` unchanged it is a
/// black silhouette on transparency — at 16 points a smudge, and beside every other icon in a Finder
/// window, broken. An application icon is the opposite kind of object: it brings its own ground, and
/// it is read at 512 points as often as at 32.
///
/// ### One drawing, not two
///
/// The geometry is never retyped. This renders `Mark.svg(for: .allThreeHold)` — the same string the
/// status item uses — and recolours it by masking, so a change to a radius or a gutter in `Mark`
/// appears here on the next build. A second hand-drawn copy is the duplicate-source shape this
/// project keeps removing, and it would drift the first time somebody nudged the outline.
///
/// ### Why the RESTING state
///
/// `.allThreeHold` is the open outline: *nothing to report*. An icon frozen in `.nothingAnswered`
/// would be a permanent claim about a status, sitting in the Finder being wrong about a laptop it is
/// not even looking at. The icon says what the app IS; the menu bar says what it has found.
///
/// ### Two tones, and no colour
///
/// White on a dark ground. `IconStateTests` and `MarkTests` between them enforce that no state is
/// distinguished by hue, because a status conveyed by colour is one some people cannot read at all.
/// An icon that introduced colour would be inventing a channel this app deliberately refuses.
public enum AppIcon {

    /// The ground. Near-black rather than pure: pure black on a dark Finder background loses its
    /// edge, and the rounded rectangle is the only thing giving the mark somewhere to sit.
    static let ground = NSColor(srgbRed: 0.11, green: 0.11, blue: 0.12, alpha: 1)

    /// macOS draws app icons as a rounded rectangle; this is the proportion Big Sur onwards uses.
    /// Expressed as a fraction so every size gets the same shape rather than the same pixel count.
    static let cornerFraction = 0.2237

    /// How much of the canvas the mark occupies. The system's own icons leave a margin; a glyph that
    /// runs to the edges reads as a sticker rather than as an icon, and at 16 points it merges with
    /// its own corners.
    static let markFraction = 0.62

    /// The icon at one size, as an image.
    ///
    /// **The compositing is explicit at every step, and that is not decoration.** Two contact sheets
    /// lied to this project in one week: one wrote opaque white and discarded the alpha channel, the
    /// other defaulted to `.copy` and erased the background it was drawn onto. Both were
    /// composition-mode defaults nobody had stated. So: the ground is filled, the glyph is built by
    /// masking white with the mark's own alpha (`.destinationIn`), and the glyph is drawn over the
    /// ground with `.sourceOver` named out loud.
    /// **Drawn into an explicit bitmap, never `NSImage.lockFocus`, and the difference is not style.**
    ///
    /// `lockFocus` renders at the backing scale of whatever display the machine happens to have, so on
    /// a Retina Mac every file came out at twice the size its name claimed — `icon_16x16.png` holding
    /// 32×32 pixels. `iconutil` refuses a set like that outright, so the failure would have surfaced
    /// at bundle time as a rejected icon rather than as a wrong one. Caught here by
    /// `everyIconsetFileIsTheSizeItsNameClaims`, which failed on all ten files the first time it ran.
    ///
    /// A bitmap with stated dimensions renders the same on any machine, including a CI runner with no
    /// display at all.
    public static func rep(points: CGFloat) -> NSBitmapImageRep? {
        guard let mark = NSImage(data: Data(Mark.svg(for: .allThreeHold).utf8)) else { return nil }
        let side = Int(points)
        guard let canvas = bitmap(side: side) else { return nil }

        draw(into: canvas) {
            let bounds = NSRect(x: 0, y: 0, width: points, height: points)
            ground.setFill()
            NSBezierPath(
                roundedRect: bounds,
                xRadius: points * cornerFraction,
                yRadius: points * cornerFraction,
            ).fill()

            let markSide = (points * markFraction).rounded()
            let inset = ((points - markSide) / 2).rounded()
            let box = NSRect(x: inset, y: inset, width: markSide, height: markSide)

            // The glyph is white shaped by the mark's alpha, and it is built in its OWN bitmap
            // because `.destinationIn` drawn against the ground would cut a hole in the ground
            // rather than clip the mark. That is entry 9's mistake in a different costume.
            if let glyph = bitmap(side: Int(markSide)) {
                draw(into: glyph) {
                    let full = NSRect(x: 0, y: 0, width: markSide, height: markSide)
                    NSColor.white.setFill()
                    full.fill()
                    mark.draw(in: full, from: .zero, operation: .destinationIn, fraction: 1)
                }
                glyph.draw(
                    in: box,
                    from: .zero,
                    operation: .sourceOver,
                    fraction: 1,
                    respectFlipped: true,
                    hints: nil,
                )
            }
        }
        return canvas
    }

    /// The icon at one size, as an image — for anything that wants to show it rather than write it.
    public static func image(points: CGFloat) -> NSImage? {
        guard let rep = rep(points: points) else { return nil }
        let image = NSImage(size: NSSize(width: points, height: points))
        image.addRepresentation(rep)
        return image
    }

    /// The icon at one size, as PNG bytes for an `.iconset`.
    public static func png(points: CGFloat) -> Data? {
        rep(points: points)?.representation(using: .png, properties: [:])
    }

    /// A bitmap whose pixel count is stated rather than inherited from a display.
    private static func bitmap(side: Int) -> NSBitmapImageRep? {
        let rep = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: side,
            pixelsHigh: side,
            bitsPerSample: 8,
            samplesPerPixel: 4,
            hasAlpha: true,
            isPlanar: false,
            colorSpaceName: .deviceRGB,
            bytesPerRow: 0,
            bitsPerPixel: 0,
        )
        // One pixel per point in this rep's own coordinates, so a drawing in points lands where the
        // arithmetic above says it does.
        rep?.size = NSSize(width: side, height: side)
        return rep
    }

    private static func draw(into rep: NSBitmapImageRep, _ body: () -> Void) {
        guard let context = NSGraphicsContext(bitmapImageRep: rep) else { return }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = context
        context.imageInterpolation = .high
        body()
        NSGraphicsContext.restoreGraphicsState()
    }

    /// The ten files an `.icns` is built from: five sizes, each at 1× and 2×.
    ///
    /// Named exactly as `iconutil` requires — it refuses a set with an unexpected filename rather
    /// than skipping it, which is the good failure and the reason this list is data rather than a
    /// loop somebody edits.
    public static let iconsetFiles: [(name: String, points: CGFloat)] = [
        ("icon_16x16.png", 16),
        ("icon_16x16@2x.png", 32),
        ("icon_32x32.png", 32),
        ("icon_32x32@2x.png", 64),
        ("icon_128x128.png", 128),
        ("icon_128x128@2x.png", 256),
        ("icon_256x256.png", 256),
        ("icon_256x256@2x.png", 512),
        ("icon_512x512.png", 512),
        ("icon_512x512@2x.png", 1024),
    ]
}
