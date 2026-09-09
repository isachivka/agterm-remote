import AppKit
import Foundation
import Testing
@testable import AgtermRemoteCore

/// The app icon, read back as pixels rather than looked at.
///
/// **This project's pictures have lied twice in a week** — entry 8 in the instruments log wrote opaque
/// white and threw the alpha away; entry 9 let `.copy` erase the background and produced an icon that
/// vanished. Both were composition defaults nobody stated, and both were caught by a person squinting
/// at a contact sheet rather than by a test. So the icon is measured: ground where the ground should
/// be, white where the mark is, nothing outside the rounded corner.
///
/// **And the probe gets a control first**, because a reader that cannot distinguish two places would
/// pass every assertion below while measuring one pixel over and over.
struct AppIconTests {

    /// Pixels of the icon at a given size, at 1× so a coordinate in points is a coordinate in pixels.
    private func rendered(points: CGFloat) throws -> NSBitmapImageRep {
        let png = try #require(AppIcon.png(points: points), "the icon did not render at \(points)")
        return try #require(NSBitmapImageRep(data: png))
    }

    /// `colorAt` indexes from the TOP-left. Stated because `MarkTests.alpha()` once assumed otherwise,
    /// measured the wrong squares for an afternoon, and reported it confidently.
    private func colour(_ rep: NSBitmapImageRep, _ x: Int, _ y: Int) -> NSColor {
        rep.colorAt(x: x, y: y) ?? .clear
    }

    /// **The control.** Two known-different places, and the probe must say they are different: the
    /// centre of the icon carries the mark's drawing, the very corner carries nothing at all. If this
    /// fails, every other assertion in this file is measuring something it cannot see.
    @Test func theProbeCanTellOnePlaceFromAnother() throws {
        let rep = try rendered(points: 512)

        let corner = colour(rep, 1, 1)
        let middle = colour(rep, rep.pixelsWide / 2, rep.pixelsHigh / 2)

        #expect(corner.alphaComponent < 0.1, "the corner should be outside the rounded rectangle")
        #expect(middle.alphaComponent > 0.9, "the middle should be inside it")
    }

    /// A bare template written into an icns is a black silhouette on transparency. The ground is what
    /// makes this an icon rather than a smudge, so its presence is asserted rather than assumed.
    @Test func thereIsAGroundAndItIsNotTransparent() throws {
        for points in [CGFloat(16), 32, 128, 512] {
            let rep = try rendered(points: points)
            // Just inside the left edge at mid-height: inside the rounded rectangle at every size,
            // and outside the mark, which is inset.
            let inside = colour(rep, max(1, rep.pixelsWide / 12), rep.pixelsHigh / 2)

            #expect(inside.alphaComponent > 0.9, "no ground at \(points)pt — the icon is a silhouette")
            #expect(inside.brightnessComponent < 0.35, "the ground is not dark at \(points)pt")
        }
    }

    /// The mark is white on that ground, which is the whole two-tone decision. Measured at the centre
    /// of the top-left square, which `Mark` fills in every state.
    @Test func theMarkIsWhiteAgainstIt() throws {
        let rep = try rendered(points: 512)
        let side = CGFloat(rep.pixelsWide)
        // The mark occupies the middle `markFraction` of the canvas on an 18-unit grid; the top-left
        // square spans units 1..8, so its centre is 4.5/18 into the mark's own box.
        let inset = side * (1 - AppIcon.markFraction) / 2
        let markSide = side * AppIcon.markFraction
        let x = Int(inset + markSide * 4.5 / Mark.grid)
        let y = Int(inset + markSide * 4.5 / Mark.grid)

        let square = colour(rep, x, y)

        #expect(square.alphaComponent > 0.9, "the mark's own square is transparent")
        #expect(square.brightnessComponent > 0.9, "the mark is not white: \(square)")
    }

    /// The corners are rounded, so the icon sits beside every other icon rather than as a full square.
    @Test func theCornersAreRoundedAtEverySize() throws {
        for points in [CGFloat(16), 32, 128, 512] {
            let rep = try rendered(points: points)

            #expect(colour(rep, 0, 0).alphaComponent < 0.1, "the top-left corner is filled at \(points)pt")
            #expect(
                colour(rep, rep.pixelsWide - 1, rep.pixelsHigh - 1).alphaComponent < 0.1,
                "the bottom-right corner is filled at \(points)pt",
            )
        }
    }

    /// Every file `iconutil` expects, at the pixel size its name claims. A set with a file whose
    /// pixels disagree with its name is refused wholesale, and the refusal happens at bundle time.
    @Test func everyIconsetFileIsTheSizeItsNameClaims() throws {
        #expect(AppIcon.iconsetFiles.count == 10)

        for file in AppIcon.iconsetFiles {
            let rep = try rendered(points: file.points)
            #expect(
                rep.pixelsWide == Int(file.points) && rep.pixelsHigh == Int(file.points),
                "\(file.name) is \(rep.pixelsWide)x\(rep.pixelsHigh), not \(Int(file.points))",
            )
        }
    }

    /// **The mark stays a template, and adding an icon must not change that.** The menu bar needs one
    /// colour plus alpha; this test is here rather than only in `MarkTests` because the temptation
    /// while making an icon is to give the drawing a colour and reuse it directly.
    @Test func theSourceDrawingIsStillATemplate() throws {
        let image = try #require(Mark.image(for: .allThreeHold))

        #expect(image.isTemplate, "the menu-bar mark stopped being a template")
        #expect(!Mark.svg(for: .allThreeHold).contains("#FFFFFF"), "the mark gained a colour for the icon's sake")
    }
}
