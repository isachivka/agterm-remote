import AppKit

/// The menu-bar mark. Ours, drawn from scratch, and the only artwork this app ships.
///
/// ### The sentence it makes
///
/// Four squares, one of them different. agterm's own mark is four squares, so this says "the thing
/// next to agterm" without being agterm's artwork — a 2×2 grid is a generic form, and none of their
/// proportions, radii or spacing are copied.
///
/// **No Android.** It was the owner's first sketch and it does not survive the size: two dots and an
/// arc inside a quarter of an 18-point square is a smudge. "One of these is not like the others"
/// carries the whole meaning with three solid squares and a fourth that is not solid.
///
/// ### Why the state lives in the fourth square
///
/// The menu bar is the only place the owner sees anything without opening a menu, and `IconState`
/// exists so that **which fact is unresolved** is legible there. A single constant mark would look
/// tidy and would throw that channel away. So the three solid squares never change — that is the
/// identity, and it is what the owner recognises — and the fourth cell says what is wrong. Its
/// resting form, when all three facts hold, is the open outline: nothing to report.
///
/// **Five shapes, never five colours.** A template image has no colour to give: the system tints it
/// black in light appearance and white in dark, and anything encoded as hue would be lost. That
/// constraint is the same one `IconStateTests` enforces for the words, and it is the right one — a
/// status conveyed by colour is one the owner cannot read at a glance and may not be able to read at
/// all.
///
/// ### Why SVG text rather than a resource file
///
/// The markup lives here as a string, not as `Icon.svg` in a bundle, because the app is assembled by
/// copying one built binary into a `.app` — a resource bundle would be a second thing to copy and a
/// silent blank icon the day somebody forgets. It is still our SVG; it is just kept where it cannot
/// go missing. `MarkTests` renders it rather than trusting it.
public enum Mark {

    /// The drawing grid: 18×18, the larger of the two sizes the mark must survive.
    ///
    /// Stroke width and the gutter are chosen so that at 16pt the outline square still reads as an
    /// outline rather than filling in: 1.5 units of stroke against a 5.5-unit square leaves a 2.5-unit
    /// hole, which is between 4 and 5 device pixels on a 2× display at 16 points.
    static let grid = 18.0

    /// What sits in the bottom-right cell for a given state. Everything else is constant.
    private static func fourth(_ state: IconState) -> String {
        return switch state {
        // Nothing checked yet: the corner is empty. An absence is the most legible shape there is at
        // this size, and it is honest — nothing has looked yet.
        case .notChecked:
            ""
        // The bridge is down: a bar, as on a stopped transport. Solid, so it cannot be confused with
        // the outline, and short, so it cannot be confused with a full square.
        case .bridgeNotRunning:
            ##"<rect x="10.75" y="12.75" width="5.5" height="2.5" rx="0.75" fill="#000000"/>"##
        // Nothing answered: a slash across the empty cell. No outline behind it — the two together
        // blot into a smudge at 16 points, measured, not guessed.
        case .nothingAnswered:
            ##"""
            <line x1="10.9" y1="16.1" x2="16.1" y2="10.9" stroke="#000000" \##
            stroke-width="1.75" stroke-linecap="round"/>
            """##
        // Somebody answered and it is not us: a lone dot where our square should be. Something is in
        // that corner and it is not the shape that belongs there.
        //
        // **The outline was behind this dot until the measurement said no.** The gap between a 1.3-unit
        // dot and the outline's inner edge is 0.7 units — about one device pixel at 16 points on a 2×
        // display — and `MarkTests` caught them merging into a filled blob, which is a fifth state that
        // looks like a sixth. The dot stands alone instead, which is both legible and the truer
        // picture: our square is not there.
        case .answeredBySomethingElse:
            ##"<circle cx="13.5" cy="13.5" r="1.75" fill="#000000"/>"##
        // All three hold: the resting mark. The fourth square is simply the odd one out, and there is
        // nothing else to say.
        case .allThreeHold:
            outlineSquare
        }
    }

    /// Stroked rather than a filled square with a hole punched in it, so the shape survives being
    /// scaled by the system rather than depending on even-odd fill rules.
    private static let outlineSquare = ##"""
        <rect x="10.75" y="10.75" width="5.5" height="5.5" rx="0.75" \##
        fill="none" stroke="#000000" stroke-width="1.5"/>
        """##

    /// The mark for a state, as SVG source.
    ///
    /// Filled `#000000` throughout; the alpha channel is the whole picture. Rounded by 1 unit: sharp
    /// corners shimmer at this size, and a full round reads as dots rather than squares.
    public static func svg(for state: IconState) -> String {
        ##"""
        <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 18 18">
          <g fill="#000000">
            <rect x="1" y="1" width="7" height="7" rx="1"/>
            <rect x="10" y="1" width="7" height="7" rx="1"/>
            <rect x="1" y="10" width="7" height="7" rx="1"/>
          </g>
          \##(fourth(state))
        </svg>
        """##
    }

    /// The image the status item shows.
    ///
    /// `isTemplate` is the whole reason this reads in both appearances: the system draws it black on
    /// a light bar and white on a dark one, and a non-template image would stay black and vanish at
    /// night. Returns nil only if the markup fails to parse, which `MarkTests` would have caught
    /// first — the caller still handles it rather than force-unwrapping into a crash on somebody's
    /// menu bar.
    public static func image(for state: IconState, points: CGFloat = 18) -> NSImage? {
        guard let image = NSImage(data: Data(svg(for: state).utf8)) else { return nil }
        image.size = NSSize(width: points, height: points)
        image.isTemplate = true
        image.accessibilityDescription = state.describedAsWords
        return image
    }
}
