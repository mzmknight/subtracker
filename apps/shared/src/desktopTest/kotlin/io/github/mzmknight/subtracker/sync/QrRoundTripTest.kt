package io.github.mzmknight.subtracker.sync

import androidx.compose.ui.graphics.toAwtImage
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Encoding a QR that nothing can read would look completely fine on screen —
 * the failure only shows up when someone points a phone at it. This decodes the
 * generated image back, so a broken payload fails the build instead.
 *
 * The camera pipeline itself still needs a real device; this covers everything
 * up to the lens.
 */
class QrRoundTripTest {

    private fun decode(link: PairingLink): String? {
        val bitmap = encodeQr(link.encode(), 512) ?: return null
        val image = bitmap.toAwtImage()
        val source = BufferedImageLuminanceSource(image)
        val result = runCatching {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
        }.getOrNull()
        return result?.text
    }

    @Test
    fun aGeneratedCodeCanBeReadBack() {
        val link = PairingLink("192.0.2.42", 47913, "497180", "DESKTOP-01")
        val text = decode(link)
        assertNotNull(text, "the generated QR could not be decoded")
        assertEquals(link, PairingLink.parse(text))
    }

    @Test
    fun awkwardDeviceNamesSurviveTheImage() {
        // Percent-encoding has to survive the QR's own character-set handling.
        val link = PairingLink("192.0.2.16", 47913, "000001", "Priya's PC (café)")
        val text = decode(link)
        assertNotNull(text)
        val parsed = PairingLink.parse(text)
        assertEquals("Priya's PC (café)", parsed?.deviceName)
        assertEquals("192.0.2.16", parsed?.host)
        assertEquals(47913, parsed?.port)
    }

    @Test
    fun aLongNameIsTruncatedSoTheCodeStaysScannable() {
        // Regression: an unbounded name produced a QR dense enough that it
        // failed to decode at all — it looked perfectly fine on screen and
        // simply never scanned.
        val link = PairingLink(
            host = "198.51.100.200",
            port = 65535,
            code = "999999",
            deviceName = "A very long device name that someone typed in because they could",
        )
        val text = decode(link)
        assertNotNull(text, "a long payload must still produce a scannable code")

        val parsed = PairingLink.parse(text)
        assertNotNull(parsed)
        // What actually matters is intact; only the cosmetic name is clipped,
        // and the full one arrives in the pairing response anyway.
        assertEquals("198.51.100.200", parsed.host)
        assertEquals(65535, parsed.port)
        assertEquals("999999", parsed.code)
        assertEquals(
            link.deviceName.take(PairingLink.MAX_NAME_IN_QR),
            parsed.deviceName,
        )
    }

    @Test
    fun addressLookupSkipsLoopback() {
        // A QR containing 127.0.0.1 is worse than no QR: it scans cleanly and
        // then fails, pointing the other device at itself.
        val addresses = localAddresses()
        assertNull(addresses.firstOrNull { it.startsWith("127.") }, "loopback must never be offered")
        assertNull(addresses.firstOrNull { it.startsWith("169.254.") }, "link-local is not routable")
    }
}
