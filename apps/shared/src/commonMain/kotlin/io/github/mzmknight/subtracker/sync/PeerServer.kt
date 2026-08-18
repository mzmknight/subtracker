package io.github.mzmknight.subtracker.sync

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import io.github.mzmknight.subtracker.data.LocalStore
import io.github.mzmknight.subtracker.data.PeerBook
import io.github.mzmknight.subtracker.data.nowEpochMillis

/**
 * The embedded sync server. Every device runs one, which is what makes this
 * peer-to-peer rather than client-server â€” whichever device you sync *from* is
 * simply the one that opened the connection.
 *
 * Binds to the local network only; there is no attempt to be reachable from the
 * internet, and pairing is required before any data moves.
 */
class PeerServer(
    private val store: LocalStore,
    private val peers: PeerBook,
    private val identity: DeviceInfo = DeviceIdentity,
    private val onSynced: (String) -> Unit = {},
) {

    private var engine: EmbeddedServer<*, *>? = null

    /** Non-null only while a pairing window is open. */
    private var pendingCode: String? = null
    private var pendingCodeExpiresAt: Long = 0

    var port: Int = PeerProtocol.DEFAULT_PORT
        private set

    val isRunning: Boolean get() = engine != null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Opens a pairing window. The code is deliberately short-lived: it is typed
     * by a human, so it is only ever safe as a one-shot exchanged for a real
     * secret.
     */
    fun beginPairing(validForMillis: Long = 180_000): String {
        val code = PeerProtocol.generatePairingCode()
        pendingCode = code
        pendingCodeExpiresAt = nowEpochMillis() + validForMillis
        return code
    }

    fun cancelPairing() {
        pendingCode = null
        pendingCodeExpiresAt = 0
    }

    /** Last handler failure, surfaced so a sync problem is diagnosable. */
    var lastFailure: String? = null
        private set

    private fun reportFailure(where: String, error: Throwable) {
        lastFailure = "$where: ${error::class.simpleName}: ${error.message}"
        println("[subtracker] peer $where failed: $error")
    }

    private fun consumePairingCode(offered: String): Boolean {
        val expected = pendingCode ?: return false
        if (nowEpochMillis() > pendingCodeExpiresAt) {
            cancelPairing()
            return false
        }
        if (offered != expected) return false
        // One shot. A code that stayed valid after use would be guessable at
        // leisure by anything else on the network.
        cancelPairing()
        return true
    }

    /** Suspends because reading back the bound port is a suspending call. */
    suspend fun start(requestedPort: Int = PeerProtocol.DEFAULT_PORT): Int {
        if (engine != null) return port

        val server = embeddedServer(CIO, port = requestedPort, host = "0.0.0.0") {
            install(ContentNegotiation) { json(json) }

            routing {
                // Lets a peer confirm what it has found before pairing.
                get(PeerProtocol.HELLO_PATH) {
                    call.respond(
                        PairResponse(
                            deviceId = identity.id,
                            deviceName = identity.name,
                            secret = "",
                        )
                    )
                }

                post(PeerProtocol.PAIR_PATH) {
                    val request = call.receive<PairRequest>()
                    if (!consumePairingCode(request.code)) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            PeerError("That pairing code is wrong or has expired."),
                        )
                        return@post
                    }
                    val secret = PeerProtocol.generateSecret()
                    peers.remember(
                        peerId = request.deviceId,
                        peerName = request.deviceName,
                        secret = secret,
                        // Where they connected from, paired with the listening
                        // port they told us — the source port is ephemeral and
                        // would be useless for calling back.
                        host = call.request.origin.remoteAddress,
                        port = request.port,
                    )
                    call.respond(
                        PairResponse(
                            deviceId = identity.id,
                            deviceName = identity.name,
                            secret = secret,
                        )
                    )
                }

                post(PeerProtocol.SYNC_PATH) {
                    // Everything here is wrapped: an unhandled exception in a
                    // Ktor handler becomes a bare 500 with an empty body, which
                    // tells the other device — and anyone debugging — nothing.
                    try {
                        val offered = call.request.headers[PeerProtocol.SECRET_HEADER].orEmpty()
                        val request = call.receive<SyncRequest>()
                        val known = peers.find(request.deviceId)

                        if (known == null || known.secret.isBlank() || known.secret != offered) {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                PeerError("This device isn't paired with yours."),
                            )
                        } else {
                            // Merge theirs in, then reply with only what they
                            // lack. Computing the reply *before* applying would
                            // send back their own records; after is both correct
                            // and smaller.
                            store.applyIncoming(request.payload)
                            val reply = store.changesFor(request.payload)

                            peers.touch(
                                request.deviceId,
                                request.deviceName,
                                call.request.origin.remoteAddress,
                                request.port,
                            )
                            onSynced(request.deviceId)

                            call.respond(
                                SyncResponse(
                                    deviceId = identity.id,
                                    deviceName = identity.name,
                                    payload = reply,
                                )
                            )
                        }
                    } catch (e: Exception) {
                        reportFailure("sync", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            PeerError("The other device hit an error: ${e.message ?: "unknown"}"),
                        )
                    }
                }
            }
        }

        server.start(wait = false)
        engine = server
        // Port 0 means "any free port", so read back what was actually bound.
        port = runCatching {
            server.engine.resolvedConnectors().firstOrNull()?.port ?: requestedPort
        }.getOrDefault(requestedPort)
        return port
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        engine = null
        cancelPairing()
    }
}

