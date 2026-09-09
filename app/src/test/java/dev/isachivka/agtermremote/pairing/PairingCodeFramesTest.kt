package dev.isachivka.agtermremote.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.EnumMap

/**
 * The only part of the live scanner this repository can execute.
 *
 * Everything else on the camera path — the permission request, the frame delivery, the surface, the
 * lifecycle — needs a device, and as of 2026-08-09 there is none: the owner's phone is not ours to
 * touch and no emulator is authorised. So this covers the seam that is both testable and most likely
 * to be silently wrong.
 *
 * **Row stride is that seam.** A camera plane pads each row out to a stride, and using `width` where
 * `rowStride` belongs shears the image without throwing anything. The result reaches the owner as
 * *"it does not seem to recognise anything"* — the sentence this requirement began with — and is
 * indistinguishable from holding the phone too far away.
 */
class PairingCodeFramesTest {

    private val profile = ConnectionProfile(
        StreamKind.DirectTcp,
        "agterm.example-homelab.invalid",
        8443,
        ByteArray(340) { ((it * 11 + 5) % 256).toByte() },
    )

    /** The payload as the laptop would put it in a code. */
    private fun codeText() = PairingCode.encodeToText(ProfileCodec.encode(profile))

    /**
     * Renders the code as a luminance plane the way a camera hands one over: `rowStride` bytes per
     * row, of which only the first `width` are image, and the padding filled with something that is
     * NOT background — 0x7F rather than white — so a decoder that reads the padding as pixels sees
     * grey noise and fails, rather than getting away with it.
     */
    private fun plane(
        modulePixels: Int = 8,
        quietModules: Int = 4,
        rowStride: Int? = null,
    ): Triple<ByteArray, Int, Int> {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.MARGIN, quietModules)
        }
        val matrix = QRCodeWriter().encode(codeText(), BarcodeFormat.QR_CODE, 1, 1, hints)
        val width = matrix.width * modulePixels
        val height = matrix.height * modulePixels
        val stride = rowStride ?: width
        val luma = ByteArray(stride * height) { 0x7F }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dark = matrix.get(x / modulePixels, y / modulePixels)
                luma[y * stride + x] = if (dark) 0x00 else 0xFF.toByte()
            }
        }
        return Triple(luma, width, height)
    }

    @Test
    fun `a frame whose rows are exactly the image width decodes`() {
        val (luma, width, height) = plane()

        assertEquals(profile, PairingCodeFrames.decode(luma, width, width, height))
    }

    /**
     * **The one that matters.** A padded plane is what a real camera delivers; if the stride is not
     * honoured the rows shear and nothing decodes.
     */
    @Test
    fun `a padded plane decodes when the stride is honoured`() {
        val (luma, width, height) = plane(rowStride = 812)

        assertEquals(profile, PairingCodeFrames.decode(luma, rowStride = 812, width = width, height = height))
    }

    /**
     * And the same plane read as though it were unpadded must NOT decode. Without this, the test
     * above would pass for a decoder that ignored the stride entirely on a frame that happened to be
     * readable anyway.
     */
    @Test
    fun `the same padded plane read at the wrong stride does not decode`() {
        val (luma, width, height) = plane(rowStride = 812)

        assertNull(PairingCodeFrames.decode(luma, rowStride = width, width = width, height = height))
    }

    @Test
    fun `a frame of nothing decodes to nothing rather than throwing`() {
        val blank = ByteArray(640 * 480) { 0x7F }

        assertNull(PairingCodeFrames.decode(blank, 640, 640, 480))
    }

    /**
     * A stride smaller than the width is not a frame, and a buffer shorter than the geometry claims
     * is not one either. Both are refused rather than indexed into: this runs on every frame the
     * camera produces, and an exception thirty times a second is a crash with a delay on it.
     */
    @Test
    fun `impossible geometry is refused rather than thrown`() {
        val (luma, width, height) = plane()

        assertNull("a stride narrower than the image", PairingCodeFrames.decode(luma, width - 1, width, height))
        assertNull("a buffer shorter than the geometry", PairingCodeFrames.decode(ByteArray(10), 640, 640, 480))
        assertNull(PairingCodeFrames.decode(luma, width, 0, height))
        assertNull(PairingCodeFrames.decode(luma, width, width, 0))
    }

    /**
     * The live path and the file path are the same decoder, so a code that decodes as pixels decodes
     * as a plane. If these two ever disagree, one of them has grown its own reader.
     */
    @Test
    fun `the frame path and the still path agree on the same code`() {
        val (luma, width, height) = plane()
        val pixels = IntArray(width * height) { i ->
            val v = luma[i].toInt() and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        val fromFrame = PairingCodeFrames.decode(luma, width, width, height)
        val fromStill = PairingCode.decode(pixels, width, height)

        assertNotNull(fromFrame)
        assertEquals(fromStill, fromFrame)
    }
}
