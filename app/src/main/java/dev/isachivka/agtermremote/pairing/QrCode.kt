package dev.isachivka.agtermremote.pairing

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.EnumMap

/**
 * Reads the text out of a QR symbol. **Nothing else.**
 *
 * ### The whole point of this file is what it does not do
 *
 * It used to. `PairingCode` read the symbol AND base64-decoded it AND parsed a payload, through a
 * decoder of its own that trimmed its input and never checked padding — so a string the Go encoder
 * refuses was a pairing code on the phone. That divergence is exactly what `wire/` exists to catch,
 * and it was sitting on the one code path that faces a lens, where nothing else could see it.
 *
 * So the split is the fix, not a tidy-up: **this returns a `String`, and [EnrollCodec] is the only
 * thing in the application that turns a string into a payload.** There is one decoder reachable from
 * the camera, it is the one the shared vectors pin, and there is nowhere for a second set of rules to
 * live.
 *
 * ### Row stride is why the frame overload takes four numbers
 *
 * A camera plane's rows are **not** `width` bytes long. The hardware pads each row out to a stride, so
 * row *n* begins at `n * rowStride` and not at `n * width`. Passing `width` where `rowStride` belongs
 * produces an image that is silently sheared — every row shifted a little further left than the one
 * above — and the failure mode is not an exception or a crash. It is *"the scanner does not seem to
 * recognise anything"*, which is **the exact sentence this whole requirement started from**, and it is
 * indistinguishable from a code held too far away. `QrCodeTest` feeds it a padded plane.
 */
object QrCode {

    /**
     * The text a QR symbol in this frame's luminance plane carries, or null.
     *
     * Null is the ordinary answer, thirty times a second, for every frame that does not happen to
     * contain a readable symbol. It is not an error and nothing is logged: a viewfinder pointed at a
     * room is *working* while it returns null.
     */
    fun textIn(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        if (luma.size < rowStride * (height - 1) + width) return null

        return textIn(
            PlanarYUVLuminanceSource(
                luma,
                // dataWidth is the STRIDE, not the image width. The crop that follows is what trims
                // the padding back off.
                rowStride,
                height,
                0,
                0,
                width,
                height,
                false,
            ),
        )
    }

    /**
     * The reader itself, for whatever produced the light.
     *
     * `YUV_420_888`'s first plane is luminance, which is the only thing a QR decoder wants, so a camera
     * frame arrives here with no conversion and no copy beyond the one the caller must make anyway.
     */
    fun textIn(source: LuminanceSource): String? {
        val bitmap = BinaryBitmap(HybridBinarizer(source))

        // TRY_HARDER matters here and costs nothing that is noticeable: the input is a photograph of a
        // screen, so it arrives rotated, skewed, glared and out of focus rather than as a clean
        // render. A reader tuned for a scanner pointed squarely at a printed label would reject frames
        // a person would call obviously readable.
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.TRY_HARDER, true)
        }

        return try {
            // QRCodeReader rather than MultiFormatReader: this app reads exactly one symbology, and
            // asking the library to consider barcodes it will never be shown is work with no upside.
            QRCodeReader().decode(bitmap, hints).text
        } catch (e: NotFoundException) {
            null
        } catch (e: Exception) {
            // ZXing throws Checksum-, Format- and other reader exceptions for a damaged or partial
            // symbol. Every one of them means the same thing to the owner - that is not a pairing
            // code - and none of them should reach a crash reporter from a camera pointed at a room.
            null
        }
    }
}
