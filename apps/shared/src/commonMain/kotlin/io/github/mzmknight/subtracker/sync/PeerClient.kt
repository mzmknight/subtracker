package io.github.mzmknight.subtracker.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import io.github.mzmknight.subtracker.data.LocalStore
import io.github.mzmknight.subtracker.data.PeerBook

class PeerException(message: String) : Exception(message)

/**
 * The initiating half of a sync. Whichever device the user taps "sync" on plays
 * this role; the other is running [PeerServer]. Both then hold the same data.
 */
class PeerClient(
    private val store: LocalStore,
    private val peers: PeerBook,
    private val identity: DeviceInfo = DeviceIdentity,
    /** This device's own listening port, so a peer can reach back later. */
    private val localPort: () -> Int = { PeerProtocol.DEFAULT_PORT },
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            // A device that has gone off the network should fail quickly rather
            // than leaving the user staring at a spinner.
            connectTimeoutMillis = 4_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    private fun base(host: String, port: Int) = "http://$host:$port"

    /** Identify a device found on the network, before committing to pairing. */
    suspend fun hello(host: String, port: Int): PairResponse = guarded {
        val response = client.get(base(host, port) + PeerProtocol.HELLO_PATH)
        if (!response.status.isSuccess()) throw PeerException("That device didn't answer properly.")
        response.body()
    }

    /**
     * Exchange the human-typed code for a durable secret, and remember the peer.
     */
    suspend fun pair(host: String, port: Int, code: String): KnownPeerSummary = guarded {
        val response = client.post(base(host, port) + PeerProtocol.PAIR_PATH) {
            contentType(ContentType.Application.Json)
            setBody(
                PairRequest(
                    code = code.trim(),
                    deviceId = identity.id,
                    deviceName = identity.name,
                    port = localPort(),
                )
            )
        }
        if (response.status == HttpStatusCode.Forbidden) {
            throw PeerException("That pairing code is wrong or has expired.")
        }
        if (!response.status.isSuccess()) {
            throw PeerException("Pairing failed (${response.status.value}).")
        }

        val paired: PairResponse = response.body()
        peers.remember(paired.deviceId, paired.deviceName, paired.secret, host, port)
        KnownPeerSummary(paired.deviceId, paired.deviceName)
    }

    /**
     * One round trip: send everything, merge what comes back.
     *
     * Sending the full state rather than a delta keeps this stateless â€” no
     * cursor to get out of step, and a payload of a few KB makes the saving
     * pointless anyway.
     */
    suspend fun sync(host: String, port: Int, peerId: String): MergeReport = guarded {
        val known = peers.find(peerId)
            ?: throw PeerException("Not paired with that device yet.")

        val mine = store.exportAll()
        val response = client.post(base(host, port) + PeerProtocol.SYNC_PATH) {
            contentType(ContentType.Application.Json)
            header(PeerProtocol.SECRET_HEADER, known.secret)
            setBody(
                SyncRequest(
                    deviceId = identity.id,
                    deviceName = identity.name,
                    port = localPort(),
                    payload = mine,
                )
            )
        }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw PeerException("That device no longer recognises this one. Pair again.")
        }
        if (!response.status.isSuccess()) {
            throw PeerException("Sync failed (${response.status.value}).")
        }

        val reply: SyncResponse = response.body()
        val report = store.applyIncoming(reply.payload)
        peers.touch(reply.deviceId, reply.deviceName, host, port)
        // applyIncoming counts "what the payload it was given is missing", which
        // is the right question on the receiving side but the wrong one here:
        // the reply holds only what *we* lacked, so measuring against it counts
        // almost every local record as freshly sent. It reported "Sent 6
        // changes" after a sync in which nothing moved at all. The peer is the
        // only side that can count this, and it just told us.
        report.copy(sentCount = reply.accepted)
    }

    /**
     * Keeps the underlying reason.
     *
     * This used to replace every failure with "Couldn't reach that device. Are
     * both on the same network?" — which asserted a cause it had not checked.
     * When Android silently blocked cleartext HTTP, that message sent everyone
     * hunting a network fault that did not exist. A wrong explanation is worse
     * than an honest one.
     */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: PeerException) {
        throw e
    } catch (e: Exception) {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
        throw PeerException("Couldn't talk to that device — $reason")
    }

    fun close() = client.close()
}

data class KnownPeerSummary(val deviceId: String, val deviceName: String)



