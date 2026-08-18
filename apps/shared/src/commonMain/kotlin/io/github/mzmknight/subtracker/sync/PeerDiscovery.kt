package io.github.mzmknight.subtracker.sync

/**
 * Finding other devices on the local network.
 *
 * Android and the desktop have completely different mDNS stacks (NsdManager vs
 * JmDNS), so each app module installs its own implementation at startup — the
 * same pattern as the database driver and the settings store.
 *
 * Discovery is a convenience, never a requirement: mDNS is genuinely unreliable
 * across Wi-Fi isolation, VPNs and Windows firewall rules, so the UI always
 * offers typing an address by hand. A sync feature that only works when
 * multicast does would be a sync feature that often doesn't work.
 */
interface PeerDiscovery {

    /** Announce this device so peers can find it. */
    fun startAdvertising(deviceId: String, deviceName: String, port: Int)

    fun stopAdvertising()

    /** [onPeersChanged] is called with the full current list on every change. */
    fun startDiscovery(onPeersChanged: (List<DiscoveredPeer>) -> Unit)

    fun stopDiscovery()

    /** Whether this platform can actually do mDNS, so the UI can say so. */
    val isSupported: Boolean
        get() = true
}

/** Used until a platform implementation is installed. Manual entry still works. */
class NoOpPeerDiscovery : PeerDiscovery {
    override fun startAdvertising(deviceId: String, deviceName: String, port: Int) = Unit
    override fun stopAdvertising() = Unit
    override fun startDiscovery(onPeersChanged: (List<DiscoveredPeer>) -> Unit) {
        onPeersChanged(emptyList())
    }
    override fun stopDiscovery() = Unit
    override val isSupported: Boolean get() = false
}

object Discovery {
    var factory: () -> PeerDiscovery = { NoOpPeerDiscovery() }

    fun create(): PeerDiscovery = runCatching { factory() }.getOrElse { NoOpPeerDiscovery() }
}

/** Keys used in the mDNS TXT record, so both platforms agree on the format. */
object DiscoveryKeys {
    const val DEVICE_ID = "id"
    const val DEVICE_NAME = "name"
}
