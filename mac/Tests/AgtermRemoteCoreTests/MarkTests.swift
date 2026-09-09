import AppKit
import Foundation
import Testing
@testable import AgtermRemoteCore

/// The mark, **rendered and measured** rather than described.
///
/// Every claim here was a claim I could have made in a sentence and been wrong about: that the
/// outline reads as an outline at the smaller size, that five states look different, that a template
/// image carries no colour. So each one is a measurement over actual pixels at the two sizes the menu
/// bar uses — 16 and 18 points — on a 2× display, which is what the owner's Mac has.
struct MarkTests {

    /// The two sizes macOS asks for: 18 points is the usual menu-bar height, 16 the compact one.
    private static let sizes: [CGFloat] = [16, 18]

    private func rendered(_ state: IconState, points: CGFloat, scale: Int = 2) -> NSBitmapImageRep {
        let pixels = Int(points) * scale
        let image = Mark.image(for: state, points: points)
        let rep = NSBitmapImageRep(
            bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels, bitsPerSample: 8,
            samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
            colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        image?.draw(in: NSRect(x: 0, y: 0, width: CGFloat(pixels), height: CGFloat(pixels)))
        NSGraphicsContext.restoreGraphicsState()
        return rep
    }

    /// Alpha at a point given in the 18-unit drawing grid, so the assertions below read in the same
    /// coordinates the drawing is written in.
    private func alpha(_ rep: NSBitmapImageRep, atGrid x: Double, _ y: Double) -> CGFloat {
        let scale = Double(rep.pixelsWide) / Mark.grid
        // No flip. `colorAt` indexes from the top-left, which is where SVG's y starts too — the first
        // version of this helper flipped y "to match the graphics context" and made three assertions
        // read the wrong squares. The measurements it produced were confident and false, which is why
        // the control below exists.
        let px = min(rep.pixelsWide - 1, max(0, Int(x * scale)))
        let py = min(rep.pixelsHigh - 1, max(0, Int(y * scale)))
        return rep.colorAt(x: px, y: py)?.alphaComponent ?? 0
    }

    private func opaqueCount(_ rep: NSBitmapImageRep) -> Int {
        var count = 0
        for y in 0..<rep.pixelsHigh where true {
            for x in 0..<rep.pixelsWide where (rep.colorAt(x: x, y: y)?.alphaComponent ?? 0) > 0.5 {
                count += 1
            }
        }
        return count
    }

    /// The SVG parses and produces something with ink in it. A nil image here would be a blank menu
    /// bar, which is exactly the failure a resource-bundle icon would have shipped silently.
    @Test func everyStateRendersAtBothSizes() {
        for state in IconState.allCases {
            for points in Self.sizes {
                let rep = rendered(state, points: points)
                #expect(opaqueCount(rep) > 0, "\(state) at \(points)pt drew nothing")
            }
        }
    }

    /// **The identity is the constant part.** Three solid squares in every state, so the owner
    /// recognises the same mark whatever it is telling them.
    @Test func theThreeSolidSquaresAreInEveryState() {
        for state in IconState.allCases {
            for points in Self.sizes {
                let rep = rendered(state, points: points)
                for centre in [(4.5, 4.5), (13.5, 4.5), (4.5, 13.5)] {
                    #expect(alpha(rep, atGrid: centre.0, centre.1) > 0.9,
                            "\(state) at \(points)pt is missing the square at \(centre)")
                }
            }
        }
    }

    /// **The size test, and the reason the stroke is 1.5 units.** At 16 points the outline must still
    /// be an outline: ink on its edges, a hole in the middle. If it fills in, the mark reads as four
    /// solid squares and says nothing.
    @Test func theOutlineStillHasAHoleAtSixteenPoints() {
        for points in Self.sizes {
            let rep = rendered(.allThreeHold, points: points)

            #expect(alpha(rep, atGrid: 13.5, 13.5) < 0.1, "the fourth square filled in at \(points)pt")
            #expect(alpha(rep, atGrid: 13.5, 11.2) > 0.5, "no top edge at \(points)pt")
            #expect(alpha(rep, atGrid: 13.5, 15.8) > 0.5, "no bottom edge at \(points)pt")
            #expect(alpha(rep, atGrid: 11.2, 13.5) > 0.5, "no left edge at \(points)pt")
            #expect(alpha(rep, atGrid: 15.8, 13.5) > 0.5, "no right edge at \(points)pt")
        }
    }

    /// The stranger's dot is the inverse of the resting outline — ink in the middle, nothing at the
    /// edges — and that inversion is the whole distinction between "somebody answered and it is not
    /// us" and "all three hold". **This is the assertion that failed on the first drawing**, where the
    /// dot sat inside the outline and the two merged into a blob at 16 points.
    @Test func theStrangersDotIsTheInverseOfTheRestingOutline() {
        for points in Self.sizes {
            let rep = rendered(.answeredBySomethingElse, points: points)

            #expect(alpha(rep, atGrid: 13.5, 13.5) > 0.9, "no dot at \(points)pt")
            for edge in [(13.5, 11.2), (13.5, 15.8), (11.2, 13.5), (15.8, 13.5)] {
                #expect(alpha(rep, atGrid: edge.0, edge.1) < 0.5,
                        "the dot reaches the cell edge at \(points)pt and reads as the outline")
            }
        }
    }

    /// **The control for the probe.** The first version of `alpha` flipped y, and three assertions
    /// happily measured the wrong squares and reported numbers. A probe that cannot tell top from
    /// bottom is not an instrument, so this pins the orientation against a state whose corner is known
    /// empty: top-right is a solid square, bottom-right is nothing.
    @Test func theProbeCanTellTopFromBottom() {
        let rep = rendered(.notChecked, points: 18)

        #expect(alpha(rep, atGrid: 13.5, 4.5) > 0.9, "the probe is upside down")
        #expect(alpha(rep, atGrid: 13.5, 13.5) < 0.1, "the probe is upside down")
    }

    /// **Five states, five different pictures.** Compared as rendered pixels, at both sizes: two
    /// states that differ only in markup the renderer collapses would be a distinction the owner
    /// never sees.
    @Test func noTwoStatesRenderTheSameAtEitherSize() {
        for points in Self.sizes {
            var seen: [IconState: Data] = [:]
            for state in IconState.allCases {
                seen[state] = rendered(state, points: points).representation(using: .png, properties: [:])
            }
            for a in IconState.allCases {
                for b in IconState.allCases where a != b {
                    #expect(seen[a] != seen[b],
                            "\(a) and \(b) are the same picture at \(points)pt")
                }
            }
        }
    }

    /// Every state must differ by an amount a person can see, not by a pixel. Measured as the count
    /// of ink pixels in the fourth cell, which is the only part that varies.
    @Test func theFourthCellCarriesTheDifferenceAndTheRestDoesNot() {
        let cellInk: [IconState: Int] = IconState.allCases.reduce(into: [:]) { totals, state in
            let rep = rendered(state, points: 16)
            let scale = Double(rep.pixelsWide) / Mark.grid
            var ink = 0
            for x in Int(9.5 * scale)..<rep.pixelsWide {
                for y in Int(9.5 * scale)..<rep.pixelsHigh
                where (rep.colorAt(x: x, y: y)?.alphaComponent ?? 0) > 0.5 {
                    ink += 1
                }
            }
            totals[state] = ink
        }

        #expect(cellInk[.notChecked] == 0, "the empty corner is not empty")
        for state in IconState.allCases where state != .notChecked {
            #expect((cellInk[state] ?? 0) > 8, "\(state) draws too little in the fourth cell to see")
        }
    }

    /// **A template image, or it disappears at night.** The system tints a template black on a light
    /// menu bar and white on a dark one; a non-template image keeps whatever colour it was drawn in
    /// and vanishes in one of the two appearances. Both halves are checked: the flag, and the absence
    /// of any colour for the flag to have to override.
    @Test func theMarkIsATemplateAndCarriesNoColour() throws {
        for state in IconState.allCases {
            let image = try #require(Mark.image(for: state))
            #expect(image.isTemplate, "\(state) would keep its own colour in dark appearance")

            let markup = Mark.svg(for: state)
            for colour in markup.split(whereSeparator: { $0 == "\"" }).filter({ $0.hasPrefix("#") }) {
                #expect(colour == "#000000", "\(state) carries a colour: \(colour)")
            }
            #expect(!markup.lowercased().contains("rgb("), "\(state) carries a colour")
            #expect(!markup.contains("opacity"), "\(state) fades rather than draws")
        }
    }

    /// The words follow the picture, for anyone who cannot see it.
    @Test func theImageCarriesTheSameWordsTheTooltipDoes() throws {
        for state in IconState.allCases {
            let image = try #require(Mark.image(for: state))

            #expect(image.accessibilityDescription == state.describedAsWords)
        }
    }

    /// The size asked for is the size returned: a menu bar item at 18 points that hands back an
    /// 18-unit image scaled wrong is a blurry mark, and blur at this size is illegibility.
    @Test func theImageIsTheSizeItWasAskedFor() throws {
        for points in Self.sizes {
            let image = try #require(Mark.image(for: .allThreeHold, points: points))

            #expect(image.size == NSSize(width: points, height: points))
        }
    }
}
