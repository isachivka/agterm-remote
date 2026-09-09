package dev.isachivka.bewareofsugar.pairing

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.EnumMap

/**
 * Reads a pairing payload out of a photograph of a QR code.
 *
 * ### Why there is no camera in here
 *
 * Pairing happens **once in the lifetime of a phone**. A live camera preview would cost seventeen
 * transitive artifacts without `camera-view` and thirty-nine with it — measured, not estimated —
 * dragging AppCompat, media3 and Guava into an application that is otherwise pure Compose and holds
 * the client certificate for a service that can read the owner's terminal sessions. That is a large
 * permanent surface to buy three fewer taps on an operation nobody repeats.
 *
 * So the platform does both halves: the system camera takes the picture, or the system file picker
 * chooses one already taken. Both are code the owner's phone already trusts, both cost nothing, and
 * **neither needs a permission** — an app that does not declare `CAMERA` may still launch the camera
 * app to take a photo for it.
 *
 * A dark room, a cracked lens or a camera that will not focus therefore cannot block pairing: the
 * file path is not a fallback bolted beside the camera path, it is the same path, and the camera is
 * one way of producing its input.
 *
 * ### The seam
 *
 * [decode] takes raw pixels rather than a `Bitmap`, so the decoding is exercised by ordinary JVM
 * tests on a real generated QR code rather than only on a device. `PairingCodeAndroid` is the thin
 * part that turns a `Bitmap` into those pixels and is the only piece that needs hardware.
 */
object PairingCode {

    /**
     * Decodes a pairing payload from an image, or returns null.
     *
     * Null covers everything the owner will actually point a camera at: a photograph of nothing, a
     * QR code belonging to something else, a blurred frame, a payload from a newer version of the
     * app. **A screen that is fed arbitrary pictures must never turn one into half a profile**, so a
     * partial decode is a failure and not a partially populated result.
     */
    fun decode(pixels: IntArray, width: Int, height: Int): ConnectionProfile? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null

        return decode(RGBLuminanceSource(width, height, pixels))
    }

    /**
     * The one decoder, whatever produced the light.
     *
     * A still image arrives as ARGB pixels and a camera frame arrives as a Y plane, and they meet
     * here. **Neither route gets its own reader**: the file route is the only one that has ever
     * completed a pairing, so it is the one with the evidence behind it, and the live path earns that
     * evidence by being the same code rather than by resembling it.
     */
    fun decode(source: com.google.zxing.LuminanceSource): ConnectionProfile? {
        val bitmap = BinaryBitmap(HybridBinarizer(source))

        // TRY_HARDER matters here and costs nothing that is noticeable: the input is a photograph of a
        // screen, so it arrives rotated, skewed, glared and out of focus rather than as a clean
        // render. A reader tuned for a scanner pointed squarely at a printed label would reject
        // frames a person would call obviously readable.
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.TRY_HARDER, true)
        }

        val text = try {
            // QRCodeReader rather than MultiFormatReader: this app reads exactly one symbology, and
            // asking the library to consider barcodes it will never be shown is work with no upside.
            QRCodeReader().decode(bitmap, hints).text
        } catch (e: NotFoundException) {
            return null
        } catch (e: Exception) {
            // ZXing throws Checksum-, Format- and other reader exceptions for a damaged or partial
            // symbol. Every one of them means the same thing to the owner - that is not a pairing
            // code - and none of them should reach a crash reporter from a camera pointed at a room.
            return null
        }

        val bytes = decodeBase64(text) ?: return null
        return ProfileCodec.decode(bytes)
    }

    /**
     * The payload travels as base64 in the QR's text mode.
     *
     * Byte mode would be denser, but a payload that survives being read as text also survives being
     * copied out of a message, pasted into a note, or typed. That flexibility costs about a third
     * more capacity on a code that measured well under the limit, and the pairing flow is exactly
     * where an owner stuck in a dark room wants an escape hatch that does not involve optics.
     *
     * `java.util.Base64` rather than `android.util.Base64`, so this file stays testable off-device.
     */
    private fun decodeBase64(text: String): ByteArray? = try {
        java.util.Base64.getDecoder().decode(text.trim())
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Encodes a payload the way [decode] expects to find it. Used to mint a code on the laptop side. */
    fun encodeToText(payload: ByteArray): String = java.util.Base64.getEncoder().encodeToString(payload)
}
