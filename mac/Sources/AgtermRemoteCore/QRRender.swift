import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation

/// **The picture of the code, and nothing about what the code says.**
///
/// The payload is built by the bridge — `enroll.EncodeToText` owns the field order, the fingerprint
/// and the expiry — and this app draws whatever string came back over the control socket without
/// parsing it. That division is the reason there is no second encoder anywhere in this repository to
/// disagree with the first one.
///
/// ### Why a renderer here at all, when the Go side once produced a PNG
///
/// Because the file was the problem. The old path ran a helper belonging to a different project,
/// wrote a PNG to the owner's Desktop, and the panel read it back — three places for a stale code to
/// hide, one of them a file that outlived the window it was minted for. The code now lives exactly as
/// long as the panel showing it.
public enum QRRender {

    /// **`M`, not the default `L`.** The generator defaults to the lowest correction level, which
    /// produces the smallest code and the one that fails first on a screen with a reflection across
    /// it or a finger over a corner. `M` costs a few modules and buys the scan back.
    ///
    /// `Q` and `H` were not chosen: the payload is a fixed ~103 bytes, and the higher levels grow the
    /// module count enough that the code on a 240-point square starts losing pixels per module —
    /// which is the other way to make a code that will not scan.
    public static let correctionLevel = "M"

    /// The smallest the code is ever drawn, in points. A phone camera needs pixels per module, and a
    /// code drawn at its native size is a smudge a few millimetres across.
    public static let minimumSize: CGFloat = 240

    /// Draws `payload` as a QR code at least [minimumSize] on a side.
    ///
    /// - Returns: nil when there is nothing to draw. **An empty payload is not a code**, and a blank
    ///   square on the panel is the worst rendering of that — it looks like it worked.
    public static func image(for payload: String, size: CGFloat = minimumSize) -> CGImage? {
        guard !payload.isEmpty else { return nil }

        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = correctionLevel
        guard let generated = filter.outputImage else { return nil }

        // **A whole-number scale, never a fractional one.** The generator emits one pixel per module;
        // scaling by 7.3 resamples them and leaves grey edges, which a phone reads slowly or not at
        // all. The failure looks like a bad camera rather than a bad image, so it is avoided by
        // construction: round the factor up, which can only ever make the code larger than asked.
        let modules = max(generated.extent.width, 1)
        let factor = max((size / modules).rounded(.up), 1)
        let scaled = generated.transformed(by: CGAffineTransform(scaleX: factor, y: factor))

        return CIContext().createCGImage(scaled, from: scaled.extent)
    }
}
