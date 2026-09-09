package dev.isachivka.agtermremote.pairing

import com.google.zxing.PlanarYUVLuminanceSource

/**
 * A camera frame's luminance, handed to the decoder the file route already uses.
 *
 * ### Why there is no conversion here
 *
 * `ImageAnalysis` delivers `YUV_420_888`, whose first plane is luminance — which is the only thing a
 * QR decoder wants. Converting it to a `Bitmap` and then to ARGB pixels would allocate two more copies
 * of every frame, thirty times a second, to throw away the colour immediately afterwards.
 * `PlanarYUVLuminanceSource` is in the zxing jar this app already ships, and it takes the plane as it
 * arrives. **The camera is what the live route buys; the decoder was already paid for.**
 *
 * ### Row stride is the whole reason this is a separate, tested function
 *
 * A camera plane's rows are **not** `width` bytes long. The hardware pads each row to a stride, so
 * row *n* begins at `n * rowStride` and not at `n * width`. Passing `width` where `rowStride` belongs
 * produces an image that is silently sheared — every row shifted a little further left than the one
 * above — and the failure mode is not an exception or a crash. It is *"the scanner does not seem to
 * recognise anything"*, which is **the exact sentence this whole requirement started from**, and it
 * would be indistinguishable from a code held too far away.
 *
 * So the stride is a parameter, and [PairingCodeFramesTest] feeds it a padded plane. That test is the
 * only executable proof of anything on the live path.
 */
object PairingCodeFrames {

    /**
     * Decodes a pairing payload from one frame's luminance plane, or returns null.
     *
     * Null is the ordinary answer, thirty times a second, for every frame that does not happen to
     * contain a readable code. It is not an error and nothing is logged: a viewfinder pointed at a
     * room is *working* while it returns null.
     */
    fun decode(luma: ByteArray, rowStride: Int, width: Int, height: Int): ConnectionProfile? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        if (luma.size < rowStride * (height - 1) + width) return null

        val source = PlanarYUVLuminanceSource(
            luma,
            // dataWidth is the STRIDE, not the image width. The crop that follows is what trims the
            // padding back off.
            rowStride,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        return PairingCode.decode(source)
    }
}
