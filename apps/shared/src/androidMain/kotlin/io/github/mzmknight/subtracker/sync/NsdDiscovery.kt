package io.github.mzmknight.subtracker.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.ConcurrentHashMap

/** TXT record values arrive as raw bytes; empty ones are treated as absent. */
private fun NsdServiceInfo.textValue(key: String): String? =
    attributes[key]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }

/**
 * Android mDNS via NsdManager.
 *
 * NsdManager only resolves one service at a time — a second resolve while one
 * is in flight fails with FAILURE_ALREADY_ACTIVE — so resolutions are queued
 * and drained one by one. That is the single most common way this API is used
 * wrongly, and it shows up as peers that intermittently never appear.
 */
class NsdDiscovery(context: Context) : PeerDiscovery {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val found = ConcurrentHashMap<String, DiscoveredPeer>()
    private var onChanged: ((List<DiscoveredPeer>) -> Unit)? = null

    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    override val isSupported: Boolean get() = nsd != null

    override fun startAdvertising(deviceId: String, deviceName: String, port: Int) {
        val manager = nsd ?: return
        stopAdvertising()

        val info = NsdServiceInfo().apply {
            serviceName = deviceName.take(30).ifBlank { "SubTracker" }
            serviceType = PeerProtocol.SERVICE_TYPE
            setPort(port)
            // The real device id travels in the TXT record. Without it the only
            // identifier a peer sees is the service name, which is the *display*
            // name — so a device cannot recognise its own advertisement and
            // lists itself as a nearby peer.
            setAttribute(DiscoveryKeys.DEVICE_ID, deviceId)
            setAttribute(DiscoveryKeys.DEVICE_NAME, deviceName)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }

        runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registrationListener = listener
        }
    }

    override fun stopAdvertising() {
        runCatching { registrationListener?.let { nsd?.unregisterService(it) } }
        registrationListener = null
    }

    override fun startDiscovery(onPeersChanged: (List<DiscoveredPeer>) -> Unit) {
        val manager = nsd ?: run {
            onPeersChanged(emptyList())
            return
        }
        onChanged = onPeersChanged
        found.clear()
        onPeersChanged(emptyList())
        stopDiscovery()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                enqueueResolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
                onChanged?.invoke(found.values.toList())
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { manager.stopServiceDiscovery(this) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        runCatching {
            manager.discoverServices(
                PeerProtocol.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )
            discoveryListener = listener
        }
    }

    @Synchronized
    private fun enqueueResolve(info: NsdServiceInfo) {
        pending.addLast(info)
        drain()
    }

    @Synchronized
    private fun drain() {
        if (resolving) return
        val manager = nsd ?: return
        val next = pending.removeFirstOrNull() ?: return
        resolving = true

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                finishResolve()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                if (host != null) {
                    found[info.serviceName] = DiscoveredPeer(
                        deviceId = info.textValue(DiscoveryKeys.DEVICE_ID) ?: info.serviceName,
                        deviceName = info.textValue(DiscoveryKeys.DEVICE_NAME) ?: info.serviceName,
                        host = host,
                        port = info.port,
                    )
                    onChanged?.invoke(found.values.toList())
                }
                finishResolve()
            }
        }

        runCatching { manager.resolveService(next, resolveListener) }
            .onFailure { finishResolve() }
    }

    @Synchronized
    private fun finishResolve() {
        resolving = false
        drain()
    }

    override fun stopDiscovery() {
        runCatching { discoveryListener?.let { nsd?.stopServiceDiscovery(it) } }
        discoveryListener = null
        synchronized(this) {
            pending.clear()
            resolving = false
        }
    }
}
