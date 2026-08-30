package io.github.mzmknight.subtracker.sync

/**
 * One address this device holds, and the adapter it belongs to.
 *
 * Both the internal [name] and the human [label] are kept because which one
 * carries the useful information depends entirely on the platform. On Android
 * the name is `wlan0` and says everything; on Windows the name is
 * `ethernet_32770` or `iftype53_32772` and says nothing, while the label names
 * the actual hardware or tunnel product.
 */
data class NetworkCandidate(
    val name: String,
    val label: String,
    val address: String,
)

/**
 * Ordering the addresses a peer might reach this device at.
 *
 * The device showing a pairing QR has to name itself, and it gets one guess.
 * Pick the wrong adapter and the code is unscannable in the only way that
 * matters: it decodes perfectly and then times out, which sends people looking
 * for a network fault that does not exist.
 *
 * This used to live inside the platform implementations, matching VPN adapters
 * against `NetworkInterface.getName()`. That is correct on Android and inert on
 * Windows, where the name is an opaque `iftype53_32772` and never contains
 * "nord", "tap", "tun" or "vpn" — so on the platform with the most virtual
 * adapters, the filter meant to skip them never fired once. It is common,
 * pure and tested now for that reason.
 *
 * The heuristic can still be wrong, so it is not the last word: the pairing
 * screen offers the runners-up too.
 */
object LocalAddresses {

    /**
     * Tunnels, virtual switches and the rest of the machinery that owns a
     * plausible-looking private address a phone across the room cannot reach.
     *
     * Matched against name *and* label, lowercased, because between the two
     * platforms either one may be the one carrying the word.
     */
    private val VIRTUAL_HINTS = listOf(
        // VPNs, by product and by protocol.
        "vpn", "nordlynx", "openvpn", "wireguard", "tailscale", "zerotier",
        "surfshark", "mullvad", "proton", "expressvpn", "hamachi", "tunnel",
        "tap-", "tun0", "wg0", "ppp",
        // Hypervisors and container runtimes: Hyper-V and WSL both hand out
        // 172.x, VMware and VirtualBox both hand out 192.168.x, and all of them
        // look exactly like a home network from the address alone.
        "vethernet", "hyper-v", "vmware", "virtualbox", "vbox", "docker", "wsl",
        "virtual",
        // Windows pseudo-adapters that carry addresses but route nowhere useful.
        "wan miniport", "teredo", "isatap", "6to4", "bluetooth", "loopback",
    )

    /** Adapters that are usually the real way onto the local network. */
    private val PHYSICAL_HINTS = listOf(
        "wlan", "wi-fi", "wifi", "wireless", "ethernet", "en0", "eth0",
    )

    /**
     * Lower sorts first. Adapter kind dominates: a tunnel holding a tidy
     * 10.x address must still lose to real hardware, which is the case that
     * was going wrong.
     */
    internal fun score(candidate: NetworkCandidate): Int {
        val text = (candidate.name + " " + candidate.label).lowercase()
        val adapter = when {
            VIRTUAL_HINTS.any { it in text } -> 40
            PHYSICAL_HINTS.any { it in text } -> 0
            // Unrecognised: not condemned, but behind anything known to be real.
            else -> 10
        }
        return adapter + addressScore(candidate.address)
    }

    private fun addressScore(address: String): Int = when {
        // The overwhelmingly common home network.
        address.startsWith("192.168.") -> 0
        address.startsWith("10.") -> 1
        isPrivate172(address) -> 1
        // 100.64/10 is carrier-grade NAT, which is what Tailscale and friends
        // hand out. Routable for them, useless as "find me on the wifi".
        isCarrierGrade(address) -> 5
        else -> 3
    }

    private fun isPrivate172(address: String): Boolean {
        if (!address.startsWith("172.")) return false
        val second = address.split(".").getOrNull(1)?.toIntOrNull() ?: return false
        return second in 16..31
    }

    private fun isCarrierGrade(address: String): Boolean {
        if (!address.startsWith("100.")) return false
        val second = address.split(".").getOrNull(1)?.toIntOrNull() ?: return false
        return second in 64..127
    }

    /**
     * Best first, duplicates removed.
     *
     * A stable sort, so two candidates the heuristic cannot separate stay in
     * the order the platform reported them rather than shuffling between calls —
     * an address that moves around on every redraw is its own bug.
     */
    fun rank(candidates: List<NetworkCandidate>): List<String> =
        candidates
            .filter { it.address.isNotBlank() }
            .sortedBy { score(it) }
            .map { it.address }
            .distinct()
}
