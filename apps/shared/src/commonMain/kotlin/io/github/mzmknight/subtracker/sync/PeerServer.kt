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
import io.github.mzmknight.subtracker.core.Lock
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
    /**
     * Called after a peer has synced *into* this device, with that peer's name
     * and what was taken from it — so the passive side can refresh and say so
     * rather than changing under the user in silence.
     *
     * Runs on a Ktor handler thread: anything touching UI state must hop to its
     * own scope first.
     */
    private val onSynced: (peerName: String, taken: MergeReport) -> Unit = { _, _ -> },
) {

    private var engine: EmbeddedServer<*, *>? = null

    /**
     * Guards the pairing window. Ktor serves requests concurrently, so without
     * this the attempt counter below is defeated by the very thing it exists to
     * stop: fire the guesses in parallel and each one reads the count before any
     * of them has incremented it.
     */
    private val pairingLock = Lock()

    /** Non-null only while a pairing window is open. */
    private var pendingCode: String? = null
    private var pendingCodeExpiresAt: Long = 0
    private var wrongAttempts = 0

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
    fun beginPairing(validForMillis: Long = 180_000): String = pairingLock.withLock {
        val code = PeerProtocol.generatePairingCode()
        pendingCode = code
        pendingCodeExpiresAt = nowEpochMillis() + validForMillis
        wrongAttempts = 0
        pairingWasRefused = false
        code
    }

    fun cancelPairing() = pairingLock.withLock { closeWindow() }

    /** Already holding [pairingLock]. */
    private fun closeWindow() {
        pendingCode = null
        pendingCodeExpiresAt = 0
        wrongAttempts = 0
    }

    /** Whether a code is currently live, so the UI can stop showing a dead one. */
    val isPairingOpen: Boolean get() = pairingLock.withLock { pendingCode != null }

    /**
     * Whether the last window closed because of wrong guesses rather than being
     * used or simply expiring.
     *
     * The distinction is the whole point of telling the user anything: a code
     * that was used needs no announcement, and one that timed out is not news,
     * but a code cancelled because something was guessing at it is worth
     * knowing about — and worth explaining, or the next code looks broken too.
     */
    var pairingWasRefused: Boolean = false
        private set

    /** Last handler failure, surfaced so a sync problem is diagnosable. */
    var lastFailure: String? = null
        private set

    private fun reportFailure(where: String, error: Throwable) {
        lastFailure = "$where: ${error::class.simpleName}: ${error.message}"
        println("[subtracker] peer $where failed: $error")
    }

    /**
     * Six digits is a million possibilities, which sounds like plenty and is
     * not: unlimited guesses over a LAN exhaust that well inside the three
     * minutes the window stays open. The shortness of the code was only ever
     * defensible because it is short-lived and single-use, and neither of those
     * helps against something that can simply try them all.
     *
     * Five wrong guesses closes the window. A human mistyping a code they can
     * see gets a couple of goes; anything working through the space does not.
     */
    private fun consumePairingCode(offered: String): Boolean = pairingLock.withLock {
        val expected = pendingCode ?: return@withLock false
        if (nowEpochMillis() > pendingCodeExpiresAt) {
            closeWindow()
            return@withLock false
        }
        if (offered != expected) {
            wrongAttempts++
            if (wrongAttempts >= MAX_WRONG_ATTEMPTS) {
                closeWindow()
                pairingWasRefused = true
            }
            return@withLock false
        }
        // One shot. A code that stayed valid after use would be guessable at
        // leisure by anything else on the network.
        closeWindow()
        true
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
                    // Wrapped like /sync below, and for the same reason: an
                    // unhandled throw here becomes a bare 500 with an empty
                    // body, which tells the other device nothing and never
                    // reaches lastFailure. Malformed JSON alone will do it, and
                    // this endpoint is reachable by anything on the network.
                    try {
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
                    } catch (e: Exception) {
                        reportFailure("pair", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            PeerError("The other device hit an error: ${e.message ?: "unknown"}"),
                        )
                    }
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
                            val taken = store.applyIncoming(request.payload)
                            val reply = store.changesFor(request.payload)

                            peers.touch(
                                request.deviceId,
                                request.deviceName,
                                call.request.origin.remoteAddress,
                                request.port,
                            )
                            // This device did not ask for any of this, so it
                            // gets told what arrived and from whom — the side
                            // that initiates already knows.
                            onSynced(request.deviceName, taken)

                            call.respond(
                                SyncResponse(
                                    deviceId = identity.id,
                                    deviceName = identity.name,
                                    payload = reply,
                                    // What we took of theirs. Only this side can
                                    // count it; see [SyncResponse.accepted].
                                    accepted = taken.received,
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

    companion object {
        /** Wrong guesses before the pairing window closes itself. */
        const val MAX_WRONG_ATTEMPTS = 5
    }
}

