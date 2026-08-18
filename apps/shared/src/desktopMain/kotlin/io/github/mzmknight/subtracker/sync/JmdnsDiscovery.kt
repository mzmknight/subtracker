package io.github.mzmknight.subtracker.sync

import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Desktop mDNS via JmDNS.
 *
 * Everything is wrapped in runCatching: mDNS fails in a dozen mundane ways —
 * no network, a VPN adapter chosen as the default route, Windows Firewall
 * blocking UDP 5353 — and none of them should crash the app or stop manual
 * pairing working.
 */
class JmdnsDiscovery : PeerDiscovery {

    private var jmdns: JmDNS? = null
    private var registered: ServiceInfo? = null
    private var listener: ServiceListener? = null

    private val found = mutableMapOf<String, DiscoveredPeer>()
    private var onChanged: ((List<DiscoveredPeer>) -> Unit)? = null

    /** JmDNS wants the fully-qualified form. */
    private val serviceType = PeerProtocol.SERVICE_TYPE + ".local."

    private fun instance(): JmDNS? {
        jmdns?.let { return it }
        return runCatching {
            // getLocalHost picks the primary interface, which is what a phone on
            // the same Wi-Fi will be able to reach.
            JmDNS.create(InetAddress.getLocalHost()).also { jmdns = it }
        }.getOrNull()
    }

    override fun startAdvertising(deviceId: String, deviceName: String, port: Int) {
        runCatching {
            val service = instance() ?: return
            stopAdvertising()
            val info = ServiceInfo.create(
                serviceType,
                deviceId,
                port,
                0,
                0,
                mapOf(
                    DiscoveryKeys.DEVICE_ID to deviceId,
                    DiscoveryKeys.DEVICE_NAME to deviceName,
                ),
            )
            service.registerService(info)
            registered = info
        }
    }

    override fun stopAdvertising() {
        runCatching { registered?.let { jmdns?.unregisterService(it) } }
        registered = null
    }

    override fun startDiscovery(onPeersChanged: (List<DiscoveredPeer>) -> Unit) {
        onChanged = onPeersChanged
        found.clear()
        onPeersChanged(emptyList())

        runCatching {
            val service = instance() ?: return
            stopDiscovery()

            val serviceListener = object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    // Resolution is asynchronous; this nudges it along.
                    runCatching { service.requestServiceInfo(event.type, event.name, 1_000) }
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    found.remove(event.name)
                    onChanged?.invoke(found.values.toList())
                }

                override fun serviceResolved(event: ServiceEvent) {
                    val info = event.info ?: return
                    val host = info.hostAddresses.firstOrNull() ?: return
                    val id = info.getPropertyString(DiscoveryKeys.DEVICE_ID) ?: event.name
                    val name = info.getPropertyString(DiscoveryKeys.DEVICE_NAME) ?: event.name

                    found[event.name] = DiscoveredPeer(
                        deviceId = id,
                        deviceName = name,
                        host = host,
                        port = info.port,
                    )
                    onChanged?.invoke(found.values.toList())
                }
            }

            service.addServiceListener(serviceType, serviceListener)
            listener = serviceListener
        }
    }

    override fun stopDiscovery() {
        runCatching { listener?.let { jmdns?.removeServiceListener(serviceType, it) } }
        listener = null
    }

    fun close() {
        stopAdvertising()
        stopDiscovery()
        runCatching { jmdns?.close() }
        jmdns = null
    }
}
