package dev.isachivka.agtermremote.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.EnumMap
import kotlin.random.Random

/**
 * The camera path, from a rendered symbol to a payload, on the JVM.
 *
 * ### What this file is the successor to
 *
 * There used to be two readers here: `PairingCode`, which turned a photograph into a
 * `ConnectionProfile` through a base64 decoder of its own, and `PairingCodeFrames`, which handed it a
 * camera plane. That base64 decoder trimmed its input and did not check padding, so a string Go
 * refuses was a pairing code on the phone - the exact divergence the shared vectors exist to catch,
 * sitting on the one path that faces a lens. Both are gone. [QrCode] reads the symbol and returns
 * **text**; [EnrollCodec] is the only thing that turns text into a payload, and it is the decoder the
 * vectors pin.
 *
 * So the codes rendered here are built from `wire/enroll-payload-vectors.json` rather than from an
 * encoder of this module's own: what goes through the lens is the same string the Go encoder is held
 * to produce.
 *
 * ### Row stride is the seam that is silently wrong
 *
 * A camera plane pads each row out to a stride, and using `width` where `rowStride` belongs shears the
 * image without throwing anything. It reaches the owner as *"it does not seem to recognise
 * anything"*, indistinguishable from holding the phone too far away.
 */
class QrCodeTest {

    /**
     * Set by `app/build.gradle.kts` from `rootProject`. `error(...)` rather than a skip: a test that
     * quietly does nothing when its fixture is missing reports success by not running.
     */
    private val wireDir = File(System.getProperty("wire.dir") ?: error("wire.dir is not set"))

    /** The text of one accept vector, which is what a Mac running this format actually shows. */
    private fun goldenVectorText(): String {
        val file = wireDir.resolve("enroll-payload-vectors.json")
        assertTrue("no vectors at ${file.absolutePath}", file.isFile)
        val array = JSONArray(file.readText())
        val texts = (0 until array.length())
            .map { array.getJSONObject(it) }
            .filter { it.getInt("version") == EnrollCodec.VERSION }
            .map { it.getString("text") }
        assertTrue("the vector set carries no version ${EnrollCodec.VERSION} code", texts.isNotEmpty())
        // The longest one, because capacity is what decides whether a symbol fits a frame at all.
        return texts.maxBy { it.length }
    }

    /**
     * Renders text as a luminance plane the way a camera hands one over: `rowStride` bytes per row, of
     * which only the first `width` are image, and the padding filled with something that is NOT
     * background - 0x7F rather than white - so a decoder that reads the padding as pixels sees grey
     * noise and fails, rather than getting away with it.
     */
    private fun plane(
        text: String = goldenVectorText(),
        modulePixels: Int = 8,
        quietModules: Int = 4,
        rowStride: Int? = null,
    ): Plane {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.MARGIN, quietModules)
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
        }
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, hints)
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
        return Plane(luma, stride, width, height)
    }

    private data class Plane(val luma: ByteArray, val stride: Int, val width: Int, val height: Int)

    private fun QrCode.readAll(p: Plane, stride: Int = p.stride): String? =
        textIn(p.luma, stride, p.width, p.height)

    // --- The path that has to work --------------------------------------------------------------

    /**
     * **The whole camera path, in one assertion.** A code the Go encoder minted, rendered as a symbol,
     * read off a camera plane, and decoded by the one decoder the vectors pin.
     */
    @Test
    fun `a code from the shared vectors reads off a camera plane and decodes`() {
        val text = goldenVectorText()
        val p = plane(text)

        val read = QrCode.readAll(p)

        assertEquals("the symbol must read back the exact string it carried", text, read)
        assertEquals(EnrollCodec.readText(text), EnrollCodec.readText(read!!))
        assertNotNull(EnrollCodec.decodeText(read))
    }

    /**
     * **The one that matters.** A padded plane is what a real camera delivers; if the stride is not
     * honoured the rows shear and nothing decodes.
     */
    @Test
    fun `a padded plane reads when the stride is honoured`() {
        val p = plane(rowStride = 1408)

        assertEquals(goldenVectorText(), QrCode.readAll(p))
    }

    /**
     * And the same plane read as though it were unpadded must NOT read. Without this, the test above
     * would pass for a decoder that ignored the stride entirely on a frame that happened to be
     * readable anyway.
     */
    @Test
    fun `the same padded plane read at the wrong stride reads nothing`() {
        val p = plane(rowStride = 1408)

        assertNull(QrCode.readAll(p, stride = p.width))
    }

    // --- What the owner will actually point a camera at -----------------------------------------

    @Test
    fun `a frame of nothing reads nothing rather than throwing`() {
        assertNull(QrCode.textIn(ByteArray(640 * 480) { 0x7F }, 640, 640, 480))
    }

    @Test
    fun `random noise reads nothing`() {
        val random = Random(1)
        val luma = ByteArray(640 * 480) { if (random.nextBoolean()) 0x00 else 0xFF.toByte() }

        assertNull(QrCode.textIn(luma, 640, 640, 480))
    }

    /**
     * A QR code that reads perfectly and is not ours - a wifi code, a URL on a poster, a payment code.
     * [QrCode] returns its text, because reading a symbol is all it does; the refusal is
     * [EnrollCodec]'s, which is the point of the split. **There is nowhere else a decision about what
     * a payload is could be made.**
     */
    @Test
    fun `a valid QR carrying something else reads as text and is refused by the codec`() {
        listOf(
            "https://example.invalid/",
            "WIFI:T:WPA;S:somenetwork;P:hunter2;;",
            "just some text",
        ).forEach { text ->
            val read = QrCode.readAll(plane(text))

            assertEquals(text, read)
            assertTrue(
                "a QR carrying \"$text\" must not pair anything",
                EnrollCodec.readText(read!!) !is EnrollDecode.Read,
            )
        }
    }

    /**
     * **The divergence this file's predecessor carried.** The retired decoder trimmed its input and
     * did not check padding, so a string Go refuses was a pairing code on the phone. The camera path
     * now runs the decoder that refuses it: a symbol carrying such a string reads back exactly as it
     * was written and pairs nothing.
     */
    @Test
    fun `a symbol carrying a mangled code reads back and pairs nothing`() {
        val text = goldenVectorText()

        val mangled = buildList {
            add(" $text ")
            add("$text\n")
            // Only when the encoder actually produced padding - a payload whose length is a multiple
            // of three carries none, and "trimmed" would then be the unmodified string.
            if (text.endsWith("=")) add(text.trimEnd('='))
        }

        mangled.forEach { candidate ->
            val read = QrCode.readAll(plane(candidate))

            assertEquals("the symbol must read back exactly what it carried", candidate, read)
            assertTrue(
                "whitespace or missing padding must not decode into a pairing code",
                EnrollCodec.readText(read!!) !is EnrollDecode.Read,
            )
        }
    }

    @Test
    fun `a damaged symbol reads nothing rather than something`() {
        val p = plane()
        // Obliterate a quarter of it, well past what error correction can recover.
        for (y in 0 until p.height / 2) for (x in 0 until p.width / 2) p.luma[y * p.stride + x] = 0xFF.toByte()

        assertNull(QrCode.readAll(p))
    }

    // --- Bounds, because this runs on every frame the camera produces ---------------------------

    /**
     * A stride smaller than the width is not a frame, and a buffer shorter than the geometry claims is
     * not one either. Both are refused rather than indexed into: an exception thirty times a second is
     * a crash with a delay on it.
     */
    @Test
    fun `impossible geometry is refused rather than thrown`() {
        val p = plane()

        assertNull("a stride narrower than the image", QrCode.textIn(p.luma, p.width - 1, p.width, p.height))
        assertNull("a buffer shorter than the geometry", QrCode.textIn(ByteArray(10), 640, 640, 480))
        assertNull(QrCode.textIn(p.luma, p.stride, 0, p.height))
        assertNull(QrCode.textIn(p.luma, p.stride, p.width, 0))
        assertNull(QrCode.textIn(ByteArray(0), 1, 1, 1))
    }

    /**
     * **The ceiling clears everything a lens can physically deliver**, which is what says it defends
     * the paste field rather than blocking the camera.
     *
     * A QR symbol tops out at 2,953 bytes in its densest mode, so no frame can carry a string past
     * [EnrollCodec.MAX_TEXT]; a clipboard has no such limit. Asserted here rather than in a comment,
     * because a ceiling set below what a symbol can hold would refuse legitimate codes and the
     * symptom would be a scanner that reads nothing.
     */
    @Test
    fun `no symbol can carry a string past the ceiling`() {
        assertTrue(
            "the ceiling of ${EnrollCodec.MAX_TEXT} is below the 2953 bytes a QR symbol can carry",
            EnrollCodec.MAX_TEXT > 2953,
        )
    }
}
