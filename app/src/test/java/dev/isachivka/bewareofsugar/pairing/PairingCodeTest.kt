package dev.isachivka.bewareofsugar.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumMap
import kotlin.random.Random

/**
 * Real QR codes, generated and decoded, on the JVM.
 *
 * The decoder is deliberately written against raw pixels rather than a `Bitmap` so this is possible:
 * a decode path only exercised on a device is a decode path exercised rarely, and the failures that
 * matter here — a photograph of the wrong thing, a damaged symbol, a payload from a newer app — are
 * all reachable without hardware.
 *
 * The encoder is in the same artifact as the decoder, so generating these costs nothing beyond the
 * one dependency already counted.
 */
class PairingCodeTest {

    private val certificate = ByteArray(385) { ((it * 13 + 7) % 256).toByte() }

    private fun profile(host: String = "laptop.example", port: Int = 51820) =
        ConnectionProfile(StreamKind.DirectTcp, host, port, certificate)

    /** Renders a payload as a QR code and returns it as pixels, the way a photograph would arrive. */
    private fun render(text: String, size: Int = 600, quietZone: Int = 4): Triple<IntArray, Int, Int> {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.MARGIN, quietZone)
        }
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] = if (matrix[x, y]) BLACK else WHITE
            }
        }
        return Triple(pixels, matrix.width, matrix.height)
    }

    private fun roundTrip(p: ConnectionProfile, size: Int = 600): ConnectionProfile? {
        val (pixels, w, h) = render(PairingCode.encodeToText(ProfileCodec.encode(p)), size)
        return PairingCode.decode(pixels, w, h)
    }

    // --- The path that has to work -------------------------------------------------------------------

    @Test
    fun `a real pairing code round trips through a real QR code`() {
        val original = profile()

        val decoded = roundTrip(original)

        assertEquals(original, decoded)
        assertTrue(
            "the certificate must survive the image byte for byte",
            certificate.contentEquals(decoded!!.bridgeCertificate),
        )
    }

    /**
     * A photograph of a screen is not a clean render. This is the smallest the code can be rendered
     * and still decode, which is the property that decides whether the owner has to hold the phone
     * steady or merely point it.
     */
    @Test
    fun `it decodes at photograph-sized resolutions`() {
        listOf(300, 400, 600, 1000).forEach { size ->
            assertNotNull("a ${size}px code must decode", roundTrip(profile(), size))
        }
    }

    @Test
    fun `a full-sized certificate and a long hostname still fit`() {
        val long = profile(host = "a-really-quite-long-hostname.bewareofsugar.keenetic.example", port = 65535)

        assertEquals(long, roundTrip(long))
    }

    // --- What the owner will actually point a camera at ----------------------------------------------

    @Test
    fun `a photograph of nothing decodes to nothing`() {
        val blank = IntArray(600 * 600) { WHITE }

        assertNull(PairingCode.decode(blank, 600, 600))
    }

    @Test
    fun `random noise decodes to nothing`() {
        val random = Random(1)
        val noise = IntArray(600 * 600) { if (random.nextBoolean()) BLACK else WHITE }

        assertNull(PairingCode.decode(noise, 600, 600))
    }

    /**
     * The failure that would be worst: a QR code that reads perfectly and is not ours. A wifi code, a
     * URL on a poster, a payment code. It must be refused by the payload check rather than accepted
     * because the image was legible.
     */
    @Test
    fun `a valid QR code carrying something else decodes to nothing`() {
        listOf(
            "https://example.invalid/",
            "WIFI:T:WPA;S:somenetwork;P:hunter2;;",
            "just some text",
            "",
        ).filter { it.isNotEmpty() }.forEach { text ->
            val (pixels, w, h) = render(text)
            assertNull("a QR carrying ${'"'}$text${'"'} must not pair anything", PairingCode.decode(pixels, w, h))
        }
    }

    @Test
    fun `a QR carrying base64 that is not a profile decodes to nothing`() {
        val (pixels, w, h) = render(PairingCode.encodeToText(ByteArray(64) { 0x41 }))

        assertNull(PairingCode.decode(pixels, w, h))
    }

    /**
     * An older app meeting a newer payload must refuse it rather than misread it — a phone that pairs
     * against a profile it decoded wrongly looks paired right up until the first connection, on a
     * network where the owner cannot tell a bad profile from a filtered one.
     */
    @Test
    fun `a payload from a newer version decodes to nothing`() {
        val bumped = ProfileCodec.encode(profile()).also { it[0] = (ProfileCodec.VERSION + 1).toByte() }
        val (pixels, w, h) = render(PairingCode.encodeToText(bumped))

        assertNull(PairingCode.decode(pixels, w, h))
    }

    @Test
    fun `a damaged symbol decodes to nothing rather than to something`() {
        val (pixels, w, h) = render(PairingCode.encodeToText(ProfileCodec.encode(profile())))
        // Obliterate a corner, well past what error correction can recover.
        for (y in 0 until h / 2) for (x in 0 until w / 2) pixels[y * w + x] = WHITE

        assertNull(PairingCode.decode(pixels, w, h))
    }

    // --- Bounds, because this is fed whatever the picker returns -------------------------------------

    @Test
    fun `malformed image dimensions are refused rather than thrown`() {
        val pixels = IntArray(100)

        assertNull(PairingCode.decode(pixels, 0, 0))
        assertNull(PairingCode.decode(pixels, -1, 10))
        assertNull("a buffer smaller than its own dimensions must not be read past its end",
            PairingCode.decode(pixels, 640, 480))
        assertNull(PairingCode.decode(IntArray(0), 1, 1))
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
