package io.github.mzmknight.subtracker.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real adapter table of whatever machine this runs on.
 *
 * [LocalAddressTest] pins the ranking against fixed inputs; this checks the
 * part that only the live JVM can answer — that the gathering step hands the
 * ranking sensible candidates in the first place. It asserts only what must
 * hold on any machine, since a developer laptop, a CI runner and a phone all
 * have completely different adapters.
 */
class LocalAddressDesktopTest {

    @Test
    fun realAddressesAreAllUsableOnes() {
        val addresses = localAddresses()

        // Printed because the ordering on a machine with VPN or Hyper-V adapters
        // is the whole point, and no fixed assertion can capture it.
        println("PROBE localAddresses() -> $addresses")

        for (address in addresses) {
            assertTrue(address.isNotBlank(), "no blank entries")
            assertFalse(address.startsWith("127."), "loopback is worse than nothing in a QR")
            assertFalse(address.startsWith("169.254."), "link-local cannot be reached by a peer")
            assertTrue(
                address.count { it == '.' } == 3,
                "IPv4 dotted quad, not a scoped IPv6 string: $address",
            )
        }
        assertTrue(addresses.size == addresses.distinct().size, "no duplicates")
    }
}
