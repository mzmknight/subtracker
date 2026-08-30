package io.github.mzmknight.subtracker.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Choosing which address to put in a pairing QR.
 *
 * Adapter naming is reproduced field for field as `NetworkInterface` actually
 * reports it, because the bug this replaces came entirely from guessing at what
 * those fields hold: on Windows the interface *name* is an opaque
 * `iftype53_32772`, and only `displayName` says "NordLynx Tunnel" — so a filter
 * reading the name skipped nothing at all. Addresses are illustrative.
 */
class LocalAddressTest {

    private fun rank(vararg candidates: NetworkCandidate) = LocalAddresses.rank(candidates.toList())

    /**
     * A Windows desktop with a VPN connected. Adapter naming is reproduced
     * exactly as Java reports it on Windows; the addresses are examples.
     *
     * Both survivors hold a private address, so nothing about the address alone
     * separates them — 10.2.0.2 looks exactly like a home network. Only the
     * adapter does, and only through its display name.
     */
    @Test
    fun aVpnTunnelDoesNotBeatRealHardwareOnWindows() {
        val ranked = rank(
            NetworkCandidate("iftype53_32772", "NordLynx Tunnel", "10.2.0.2"),
            NetworkCandidate("ethernet_32770", "Acme Gigabit Ethernet Controller", "192.168.1.24"),
        )
        assertEquals(listOf("192.168.1.24", "10.2.0.2"), ranked)
    }

    /**
     * The ordering must not depend on which adapter the OS happens to enumerate
     * first. Before this, the two tied and enumeration order decided it — so the
     * right answer was luck, and luck that varies by machine.
     */
    @Test
    fun enumerationOrderDoesNotDecideIt() {
        val physical =
            NetworkCandidate("ethernet_32770", "Acme Gigabit Ethernet Controller", "192.168.1.24")
        val tunnel = NetworkCandidate("iftype53_32772", "NordLynx Tunnel", "10.2.0.2")

        assertEquals(rank(physical, tunnel), rank(tunnel, physical))
        assertEquals("192.168.1.24", rank(tunnel, physical).first())
    }

    /**
     * Hyper-V, WSL, VMware and Docker are the nastiest case: they hand out
     * addresses in exactly the ranges a home network uses, so the address is no
     * evidence whatsoever.
     */
    @Test
    fun virtualSwitchesLoseDespiteHomeLookingAddresses() {
        val ranked = rank(
            NetworkCandidate("ethernet_5", "vEthernet (WSL (Hyper-V firewall))", "172.20.16.1"),
            NetworkCandidate("ethernet_9", "VMware Virtual Ethernet Adapter for VMnet8", "192.168.146.1"),
            NetworkCandidate("ethernet_3", "VirtualBox Host-Only Ethernet Adapter", "192.168.56.1"),
            NetworkCandidate("ethernet_7", "Hyper-V Virtual Ethernet Adapter", "172.28.80.1"),
            NetworkCandidate("ethernet_32770", "Intel(R) Wi-Fi 6E AX211 160MHz", "192.168.1.42"),
        )
        assertEquals("192.168.1.42", ranked.first(), "the only adapter that reaches the house wifi")
        assertEquals(5, ranked.size, "the others are still offered, just not first")
    }

    /** Tailscale is routable, but not the answer to "find me on this wifi". */
    @Test
    fun carrierGradeNatSinksBelowTheLan() {
        val ranked = rank(
            NetworkCandidate("iftype53_32769", "Tailscale Tunnel", "100.101.102.103"),
            NetworkCandidate("ethernet_32770", "Acme Gigabit Ethernet Controller", "192.168.1.24"),
        )
        assertEquals(listOf("192.168.1.24", "100.101.102.103"), ranked)
    }

    /**
     * Android reports the useful word in `name`, where Windows reports it in
     * `displayName`. Reading only one of the two is how this broke.
     */
    @Test
    fun androidStyleNamesStillRankWifiFirst() {
        val ranked = rank(
            NetworkCandidate("rmnet_data0", "rmnet_data0", "10.117.8.44"),
            NetworkCandidate("wlan0", "wlan0", "192.168.1.31"),
        )
        assertEquals("192.168.1.31", ranked.first())
    }

    @Test
    fun aTapAdapterIsRecognisedByItsDisplayNameAlone() {
        // Nothing in "iftype53_32770" hints at what this is.
        val ranked = rank(
            NetworkCandidate("iftype53_32770", "TAP-NordVPN Windows Adapter V9", "10.8.0.6"),
            NetworkCandidate("ethernet_32770", "Acme Gigabit Ethernet Controller", "192.168.1.24"),
        )
        assertEquals("192.168.1.24", ranked.first())
    }

    @Test
    fun theOnlyAddressIsUsedEvenIfItLooksUnusual() {
        // A single candidate is the answer whatever it scores — offering nothing
        // is strictly worse than offering something the user can sanity-check.
        val ranked = rank(NetworkCandidate("iftype53_1", "Some Tunnel", "10.2.0.2"))
        assertEquals(listOf("10.2.0.2"), ranked)
    }

    @Test
    fun blanksAreDroppedAndDuplicatesCollapse() {
        val ranked = rank(
            NetworkCandidate("ethernet_0", "Acme Gigabit Ethernet", ""),
            NetworkCandidate("ethernet_1", "Acme Gigabit Ethernet", "192.168.1.24"),
            NetworkCandidate("ethernet_2", "Acme Gigabit Ethernet", "192.168.1.24"),
        )
        assertEquals(listOf("192.168.1.24"), ranked)
    }

    @Test
    fun noAddressesIsNotAFailure() {
        assertTrue(LocalAddresses.rank(emptyList()).isEmpty())
    }
}
