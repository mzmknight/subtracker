package io.github.mzmknight.subtracker.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingLinkTest {

    @Test
    fun roundTripsThroughAQrPayload() {
        val link = PairingLink("192.0.2.42", 47913, "636710", "DESKTOP-01")
        val parsed = PairingLink.parse(link.encode())
        assertEquals(link, parsed)
    }

    @Test
    fun survivesDeviceNamesWithAwkwardCharacters() {
        // Device names are user-supplied and routinely contain spaces, and the
        // Windows computer name can carry a hyphen or an apostrophe.
        val names = listOf(
            "Priya's PC",
            "Living Room Laptop",
            "DESKTOP-4F2A9",
            "Pixel 9 Pro",
            "café-machine",
        )
        for (name in names) {
            val link = PairingLink("192.0.2.5", 47913, "000123", name)
            val encoded = link.encode()
            assertEquals(name, PairingLink.parse(encoded)?.deviceName, "failed for \"$name\"")
            // The encoded form must not contain raw spaces, which would break
            // the QR payload when scanners normalise whitespace.
            assertEquals(false, encoded.contains(' '), "encoded form contains a raw space: $encoded")
        }
    }

    @Test
    fun aMissingNameIsFine() {
        val link = PairingLink("192.0.2.5", 47913, "000123")
        val parsed = PairingLink.parse(link.encode())
        assertEquals("", parsed?.deviceName)
        assertEquals("192.0.2.5", parsed?.host)
    }

    @Test
    fun rejectsAnythingThatIsNotOurs() {
        // A scanner sees every QR pointed at it — a wifi config, a URL, a
        // payment code. None of those should be treated as a pairing offer.
        assertNull(PairingLink.parse("https://example.com/pair?h=203.0.113.4&p=47913&c=123456"))
        assertNull(PairingLink.parse("WIFI:S:MyNetwork;T:WPA;P:hunter2;;"))
        assertNull(PairingLink.parse("just some text"))
        assertNull(PairingLink.parse(""))
        assertNull(PairingLink.parse("subtracker://something-else?h=203.0.113.4"))
    }

    @Test
    fun rejectsIncompleteOrNonsensicalLinks() {
        assertNull(PairingLink.parse("subtracker://pair?p=47913&c=123456"), "no host")
        assertNull(PairingLink.parse("subtracker://pair?h=203.0.113.4&c=123456"), "no port")
        assertNull(PairingLink.parse("subtracker://pair?h=203.0.113.4&p=47913"), "no code")
        assertNull(PairingLink.parse("subtracker://pair?h=203.0.113.4&p=notaport&c=123456"))
        assertNull(PairingLink.parse("subtracker://pair?h=203.0.113.4&p=0&c=123456"), "port out of range")
        assertNull(PairingLink.parse("subtracker://pair?h=203.0.113.4&p=99999&c=123456"))
        assertNull(PairingLink.parse("subtracker://pair?h=&p=47913&c=123456"), "blank host")
    }

    @Test
    fun toleratesCaseInTheScheme() {
        val parsed = PairingLink.parse("SubTracker://pair?h=192.0.2.5&p=47913&c=999888")
        assertEquals("192.0.2.5", parsed?.host)
        assertEquals(999888.toString(), parsed?.code)
    }
}
