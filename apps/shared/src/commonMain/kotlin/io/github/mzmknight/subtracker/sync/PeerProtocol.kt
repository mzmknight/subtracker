package io.github.mzmknight.subtracker.sync

import kotlin.random.Random
import kotlinx.serialization.Serializable

/**
 * The device-to-device protocol.
 *
 * Two endpoints and one round trip:
 *
 *   POST /pair  { code }            -> { deviceId, deviceName, secret }
 *   POST /sync  { payload } +secret -> { payload }
 *
 * The sync exchange is symmetric on purpose. The initiator sends everything it
 * has; the host merges it, replies with whatever the initiator was missing, and
 * the initiator merges that. Both sides run the identical merge, so a single
 * round trip converges and neither side is "the server".
 */

@Serializable
data class PairRequest(
    val code: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    /**
     * The caller's own listening port, so the host can reach back later.
     * The connection's source port is ephemeral and useless for that.
     */
    val port: Int = 0,
)

@Serializable
data class PairResponse(
    val deviceId: String = "",
    val deviceName: String = "",
    val secret: String = "",
    val protocol: Int = SyncPayload.PROTOCOL_VERSION,
)

@Serializable
data class SyncRequest(
    val deviceId: String = "",
    val deviceName: String = "",
    /** The caller's listening port, so the host keeps a usable address for it. */
    val port: Int = 0,
    val payload: SyncPayload = SyncPayload(),
)

@Serializable
data class SyncResponse(
    val deviceId: String = "",
    val deviceName: String = "",
    /** Only what the caller is missing or holds a stale copy of. */
    val payload: SyncPayload = SyncPayload(),
    /**
     * How many of the caller's records this device actually took.
     *
     * The caller cannot work this out for itself. It sends everything it has
     * without knowing what the peer already holds, and the reply contains only
     * what the *caller* was missing — so counting locally answers a different
     * question and gets it wrong. This is the only place the number exists.
     *
     * Absent from a protocol 1 peer, where it decodes as 0 and the caller simply
     * says nothing about what it sent, which is what it did before this existed.
     */
    val accepted: Int = 0,
    val protocol: Int = SyncPayload.PROTOCOL_VERSION,
)

@Serializable
data class PeerError(val message: String)

object PeerProtocol {
    const val PAIR_PATH = "/pair"
    const val SYNC_PATH = "/sync"
    const val HELLO_PATH = "/hello"

    /** Header carrying the shared secret established during pairing. */
    const val SECRET_HEADER = "X-SubTracker-Secret"

    /** mDNS service type used to find other devices on the network. */
    const val SERVICE_TYPE = "_subtracker._tcp"

    const val DEFAULT_PORT = 47_913

    /**
     * Six digits, shown on one device and typed into the other.
     *
     * Short because a human retypes it. That is only safe because it is valid
     * for a few minutes, on the local network, and is exchanged once for a
     * proper long secret — it never authenticates anything by itself again.
     */
    fun generatePairingCode(random: Random = Random.Default): String =
        (0 until 6).joinToString("") { random.nextInt(10).toString() }

    /** 32 characters of the alphabet, used for every request after pairing. */
    fun generateSecret(random: Random = Random.Default): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (0 until 32).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}

/** A device found on the local network, before or after pairing. */
data class DiscoveredPeer(
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val paired: Boolean = false,
)
