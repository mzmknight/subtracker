package io.github.mzmknight.subtracker.data

import io.github.mzmknight.subtracker.db.SubTrackerDatabase

data class KnownPeer(
    val id: String,
    val name: String,
    val lastSyncAt: Long,
    val secret: String,
    val host: String,
    val port: Int,
) {
    /** Whether we know where to find this peer without discovery. */
    val hasAddress: Boolean get() = host.isNotBlank() && port > 0
}

/**
 * Devices this one has paired with: the secret shared with each, and where it
 * was last reachable.
 *
 * The address matters as much as the secret. Without it a paired device could
 * only be synced again if mDNS rediscovered it, and since pairing codes are
 * single-use there was no way back when discovery failed.
 *
 * Deliberately not syncable: the secret is between two devices, and shipping it
 * onward would hand a third device the keys to both.
 */
class PeerBook(database: SubTrackerDatabase) {

    private val queries = database.subTrackerQueries

    private fun map(
        peerId: String,
        peerName: String,
        lastSyncAt: Long,
        secret: String,
        host: String,
        port: Long,
    ) = KnownPeer(peerId, peerName, lastSyncAt, secret, host, port.toInt())

    fun all(): List<KnownPeer> = queries.selectPeers().executeAsList().map {
        map(it.peer_id, it.peer_name, it.last_sync_at, it.secret, it.host, it.port)
    }

    fun find(peerId: String): KnownPeer? =
        queries.selectPeer(peerId).executeAsOneOrNull()?.let {
            map(it.peer_id, it.peer_name, it.last_sync_at, it.secret, it.host, it.port)
        }

    fun remember(peerId: String, peerName: String, secret: String, host: String, port: Int) {
        queries.upsertPeer(peerId, peerName, nowEpochMillis(), secret, host, port.toLong())
    }

    /** Records a successful sync, refreshing the address it was reached at. */
    fun touch(peerId: String, peerName: String, host: String, port: Int) {
        queries.touchPeer(nowEpochMillis(), peerName, host, port.toLong(), peerId)
    }

    fun forget(peerId: String) {
        queries.deletePeer(peerId)
    }
}
