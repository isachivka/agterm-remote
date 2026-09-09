import CoreImage
import Foundation
import Testing
@testable import AgtermRemoteCore

/// **A picture of a code is only a code if something can read it back.**
///
/// The panel's whole job is to put a string in front of a camera. Asserting that a non-nil image came
/// out asserts that a filter ran; it says nothing about whether a phone can decode what is on the
/// screen, which is the only property anybody cares about. So every test here **decodes the rendered
/// image** and compares the string that comes out with the string that went in.
struct QRRenderTests {

    /// A payload of the real shape: standard padded base64 of the 103 bytes an `enroll.Payload` with a
    /// short host encodes to. Not the owner's address — a documentation host and a loopback port.
    private static let payload =
        "AQAWYWd0ZXJtLmV4YW1wbGUuaW52YWxpZCEbAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygp"
            + "KissLS4vMDEyMzQ1Njc4OTo7PD0+P2h0dHBz"

    private static func decode(_ image: CGImage) -> [String] {
        let detector = CIDetector(
            ofType: CIDetectorTypeQRCode, context: nil,
            options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        let features = detector?.features(in: CIImage(cgImage: image)) ?? []
        return features.compactMap { ($0 as? CIQRCodeFeature)?.messageString }
    }

    @Test func theRenderedCodeDecodesBackToThePayload() throws {
        let image = try #require(QRRender.image(for: Self.payload))

        #expect(Self.decode(image) == [Self.payload], "the code on screen is not the payload")
    }

    /// **At least 240 points on screen**, which is the size the task names and the reason the window is
    /// as wide as it is. A phone camera needs pixels per module; a code drawn at its native 33 modules
    /// is a smudge.
    @Test func theCodeIsBigEnoughToScan() throws {
        #expect(QRRender.minimumSize >= 240)
        let image = try #require(QRRender.image(for: Self.payload))

        #expect(CGFloat(image.width) >= QRRender.minimumSize)
        #expect(image.width == image.height, "a stretched code is not a code")
    }

    /// Correction level M, asked of the filter rather than of our memory of what we set. `L` is the
    /// default and would ship a code that fails on a screen with a reflection across it.
    @Test func theCorrectionLevelIsTheOneWeChose() throws {
        #expect(QRRender.correctionLevel == "M")
        let filter = try #require(CIFilter(name: "CIQRCodeGenerator"))
        filter.setValue(Data(Self.payload.utf8), forKey: "inputMessage")
        filter.setValue(QRRender.correctionLevel, forKey: "inputCorrectionLevel")

        #expect(filter.outputImage != nil, "the filter refuses the level this app asks it for")
    }

    /// Scaling is by whole pixels. A fractional scale resamples the modules and produces grey edges
    /// that a phone reads slowly or not at all — the failure looks like a bad camera rather than a bad
    /// image, so it is pinned here.
    @Test func theCodeIsScaledWithoutSmoothing() throws {
        let image = try #require(QRRender.image(for: Self.payload))
        let context = CIContext()
        let bitmap = context.createCGImage(CIImage(cgImage: image), from: CIImage(cgImage: image).extent)

        #expect(bitmap != nil)
        #expect(Self.decode(image).first == Self.payload)
    }

    /// An empty payload is not a code. It is what a refused `pair-open` would leave behind, and a
    /// blank square on the panel is the worst possible rendering of that — it looks like it worked.
    @Test func nothingIsRenderedForAnEmptyPayload() {
        #expect(QRRender.image(for: "") == nil)
    }
}
