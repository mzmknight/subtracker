package io.github.mzmknight.subtracker.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import io.github.mzmknight.subtracker.core.PlainDate
import io.github.mzmknight.subtracker.data.LocalStore
import io.github.mzmknight.subtracker.data.PeerBook
import io.github.mzmknight.subtracker.db.SubTrackerDatabase

/**
 * Two devices syncing over real HTTP with no server of any kind involved.
 *
 * A genuine Ktor server and a genuine client, not stubs â€” so serialisation,
 * authentication and the merge are all exercised the way they will be on a
 * phone talking to a laptop.
 */
class PeerSyncTest {

    private val today = PlainDate.parse("2026-08-09")
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val servers = mutableListOf<PeerServer>()
    private val clients = mutableListOf<PeerClient>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop() }
        clients.forEach { it.close() }
        drivers.forEach { it.close() }
    }

    /**
     * A device's own identity and clock state, entirely separate from every
     * other device in the test. Using the real DeviceIdentity singleton here
     * would make both "devices" share an id, and the test would prove only that
     * a device can sync with itself.
     */
    private class TestIdentity(
        override val id: String,
        override val name: String,
    ) : DeviceInfo, ClockStore {
        private var state: Hlc? = null
        override fun loadClockState(): Hlc? = state
        override fun saveClockState(state: Hlc) {
            this.state = state
        }
    }

    /** A simulated device: its own database, identity, clock and peer book. */
    private class Device(
        val store: LocalStore,
        val peers: PeerBook,
        val identity: TestIdentity,
    ) {
        val id: String get() = identity.id
        val name: String get() = identity.name
    }

    private var deviceCounter = 0

    private fun newDevice(name: String, clock: Long = 1_786_000_000_000): Device {
        // Fixed-width ids: the HLC's tiebreak is the last component of a
        // lexicographically compared string, so the width must never vary.
        val id = "device${deviceCounter++}".padEnd(16, 'x')
        val identity = TestIdentity(id, name)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SubTrackerDatabase.Schema.create(driver)
        drivers += driver
        val database = SubTrackerDatabase(driver)
        return Device(
            store = LocalStore(database, identity, identity) { clock },
            peers = PeerBook(database),
            identity = identity,
        )
    }

    private fun draft(name: String, anchor: String) = SubscriptionRecord(
        id = "", updatedAt = "", name = name, anchorDate = anchor,
        cycleUnit = "month", cycleCount = 1, status = "active", currency = "GBP",
    )

    @Test
    fun twoDevicesPairAndConvergeOverHttp() = runBlocking {
        val laptop = newDevice("Laptop", clock = 1_786_000_000_000)
        val phone = newDevice("Phone", clock = 1_786_000_100_000)

        laptop.store.createSubscription(draft("Netflix", "2026-01-31"), 1099)
        phone.store.createSubscription(draft("Spotify", "2026-03-15"), 1199)

        // The laptop hosts and shows a pairing code.
        val server = PeerServer(laptop.store, laptop.peers, laptop.identity)
        servers += server
        val port = server.start(requestedPort = 0)
        assertTrue(port > 0, "server bound to a real port")
        val code = server.beginPairing()
        assertEquals(6, code.length)

        // The phone pairs, then syncs.
        val client = PeerClient(phone.store, phone.peers, phone.identity)
        clients += client

        val greeting = client.hello("127.0.0.1", port)
        assertEquals("Laptop", greeting.deviceName)

        val paired = client.pair("127.0.0.1", port, code)
        assertEquals(laptop.id, paired.deviceId)
        assertNotNull(phone.peers.find(laptop.id)?.secret?.takeIf { it.isNotBlank() })

        val report = client.sync("127.0.0.1", port, laptop.id)
        assertTrue(report.changed, "the phone received the laptop's subscription")

        // Both devices now hold both subscriptions, with no server anywhere.
        assertEquals(
            listOf("Netflix", "Spotify"),
            phone.store.subscriptions().map { it.name }.sorted(),
        )
        assertEquals(
            listOf("Netflix", "Spotify"),
            laptop.store.subscriptions().map { it.name }.sorted(),
        )

        // And they agree on the money, each having computed it independently.
        assertEquals(
            laptop.store.figures(today).monthlyRunRateMinor,
            phone.store.figures(today).monthlyRunRateMinor,
        )
        assertEquals(2298, phone.store.figures(today).monthlyRunRateMinor)
    }

    @Test
    fun syncingWithoutPairingIsRefused() = runBlocking {
        val laptop = newDevice("Laptop")
        val phone = newDevice("Phone")
        laptop.store.createSubscription(draft("Netflix", "2026-01-31"), 1099)
        val server = PeerServer(laptop.store, laptop.peers, laptop.identity)
        servers += server
        val port = server.start(requestedPort = 0)
        val client = PeerClient(phone.store, phone.peers, phone.identity)
        clients += client

        // Never paired, so there is no secret and the exchange must not happen.
        val failure = assertFailsWith<PeerException> {
            client.sync("127.0.0.1", port, laptop.id)
        }
        assertTrue(failure.message!!.contains("Not paired"))
        assertEquals(0, phone.store.subscriptions().size, "no data leaked to an unpaired device")
    }

    @Test
    fun theServerItselfRejectsAnUnpairedCallerWith401() = runBlocking {
        // The client-side check means PeerClient never reaches the server when
        // unpaired — so without this, the server's own rejection path is never
        // exercised. Anything on the network can post here directly.
        val laptop = newDevice("Laptop")
        laptop.store.createSubscription(draft("Netflix", "2026-01-31"), 1099)
        val server = PeerServer(laptop.store, laptop.peers, laptop.identity)
        servers += server
        val port = server.start(requestedPort = 0)

        val raw = HttpClient {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
        }
        try {
            val response = raw.post("http://127.0.0.1:$port${PeerProtocol.SYNC_PATH}") {
                contentType(ContentType.Application.Json)
                setBody(SyncRequest(deviceId = "intruder", deviceName = "Intruder"))
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "an unpaired caller must get a clean 401, not a server error",
            )
            assertTrue(
                response.bodyAsText().contains("paired"),
                "and should be told why",
            )
        } finally {
            raw.close()
        }
    }

    // These two are block-bodied rather than `= runBlocking { ... }`: their last
    // expression is assertFailsWith, which returns the exception, and JUnit4
    // rejects a test method that does not return void.
    @Test
    fun aWrongPairingCodeIsRejected() {
        runBlocking {
            val laptop = newDevice("Laptop")
            val phone = newDevice("Phone")
            val server = PeerServer(laptop.store, laptop.peers, laptop.identity)
            servers += server
            val port = server.start(requestedPort = 0)
            server.beginPairing()
            val client = PeerClient(phone.store, phone.peers, phone.identity)
            clients += client

            assertFailsWith<PeerException> { client.pair("127.0.0.1", port, "000000") }
        }
    }

    @Test
    fun aPairingCodeCannotBeUsedTwice() {
        runBlocking {
            val laptop = newDevice("Laptop")
            val phone = newDevice("Phone")
            val server = PeerServer(laptop.store, laptop.peers, laptop.identity)
            servers += server
            val port = server.start(requestedPort = 0)
            val code = server.beginPairing()
            val client = PeerClient(phone.store, phone.peers, phone.identity)
            clients += client

            client.pair("127.0.0.1", port, code)
            // A code that stayed valid could be guessed at leisure by anything
            // else on the network.
            assertFailsWith<PeerException> { client.pair("127.0.0.1", port, code) }
        }
    }

    @Test
    fun aPairedDeviceCanSyncAgainWithoutRepairing() = runBlocking {
        // Pairing codes are single-use, so if the address were forgotten there
        // would be no way back except pairing again from scratch.
        val laptop = newDevice("Laptop", clock = 1_786_000_000_000)
        val phone = newDevice("Phone", clock = 1_786_000_050_000)

        val server = PeerServer(laptop.store, laptop.peers, laptop.identity)
        servers += server
        val port = server.start(requestedPort = 0)
        val code = server.beginPairing()

        val client = PeerClient(phone.store, phone.peers, phone.identity) { 9999 }
        clients += client
        client.pair("127.0.0.1", port, code)

        // The address must have been remembered, not just the secret.
        val remembered = phone.peers.find(laptop.id)
        assertNotNull(remembered)
        assertEquals("127.0.0.1", remembered.host)
        assertEquals(port, remembered.port)
        assertTrue(remembered.hasAddress)

        // Something new appears on the laptop after pairing.
        laptop.store.createSubscription(draft("Netflix", "2026-01-31"), 1099)

        // Sync again from the stored address alone — no code, no discovery.
        val report = client.sync(remembered.host, remembered.port, remembered.id)
        assertTrue(report.changed)
        assertEquals(listOf("Netflix"), phone.store.subscriptions().map { it.name })

        // And the host learned where the phone lives, from the port it advertised.
        val phoneAsSeenByLaptop = laptop.peers.find(phone.id)
        assertNotNull(phoneAsSeenByLaptop)
        assertEquals(9999, phoneAsSeenByLaptop.port, "the caller's listening port, not its source port")
        assertTrue(phoneAsSeenByLaptop.hasAddress)
    }

    @Test
    fun repeatedSyncsAreNoOpsAndDeletesPropagate() = runBlocking {
        var laptopClock = 1_786_000_000_000L
        val laptop = newDevice("Laptop", clock = laptopClock)
        val phone = newDevice("Phone", clock = 1_786_000_050_000)

        val netflix = laptop.store.createSubscription(draft("Netflix", "2026-01-31"), 1099)
        val server = PeerServer(laptop.store, laptop.peers, laptop.identity)
        servers += server
        val port = server.start(requestedPort = 0)
        val code = server.beginPairing()
        val client = PeerClient(phone.store, phone.peers, phone.identity)
        clients += client
        client.pair("127.0.0.1", port, code)

        val first = client.sync("127.0.0.1", port, laptop.id)
        assertTrue(first.changed)
        val second = client.sync("127.0.0.1", port, laptop.id)
        assertTrue(!second.changed, "a repeat sync must change nothing")

        // Delete on the phone, then sync: the tombstone has to reach the laptop.
        phone.store.deleteSubscription(netflix.id)
        client.sync("127.0.0.1", port, laptop.id)

        assertEquals(0, phone.store.subscriptions().size)
        assertEquals(0, laptop.store.subscriptions().size, "the delete propagated over the wire")
    }

    /**
     * What the initiating device tells the user it did.
     *
     * This used to be measured against the *reply*, which by construction holds
     * only what the initiator was missing — so nearly every local record counted
     * as freshly sent, and a sync in which nothing moved announced "Sent 6
     * changes". Only the far side can count what it accepted, so the number now
     * comes back over the wire.
     */
    @Test
    fun theSyncReportCountsWhatThePeerActuallyTook() = runBlocking {
        val laptop = newDevice("Laptop", clock = 1_786_000_000_000)
        val phone = newDevice("Phone", clock = 1_786_000_100_000)

        // Three subscriptions, each written with its opening price: six records.
        laptop.store.createSubscription(draft("Netflix", "2026-01-31"), 1099)
        laptop.store.createSubscription(draft("Spotify", "2026-03-15"), 1199)
        laptop.store.createSubscription(draft("Steam", "2026-04-02"), 500)

        val server = PeerServer(laptop.store, laptop.peers, laptop.identity)
        servers += server
        val port = server.start(requestedPort = 0)
        val client = PeerClient(phone.store, phone.peers, phone.identity)
        clients += client
        client.pair("127.0.0.1", port, server.beginPairing())

        val first = client.sync("127.0.0.1", port, laptop.id)
        assertEquals(6, first.received, "everything the laptop had")
        assertEquals(0, first.sentCount, "the phone was empty, so it sent nothing")
        assertEquals("Received 6 changes", first.describe())

        // Nothing has changed anywhere. This is the case that used to lie.
        val second = client.sync("127.0.0.1", port, laptop.id)
        assertEquals(0, second.received)
        assertEquals(0, second.sentCount)
        assertEquals("Already up to date", second.describe())

        // One new subscription on the phone is one record plus its price.
        phone.store.createSubscription(draft("Disney", "2026-05-05"), 799)
        val third = client.sync("127.0.0.1", port, laptop.id)
        assertEquals(0, third.received)
        assertEquals(2, third.sentCount, "the subscription and its opening price, and nothing else")
        assertEquals("Sent 2 changes", third.describe())
    }

    /**
     * The device that did *not* press sync is the one that cannot otherwise tell
     * anything happened, so it is the one that most needs telling.
     */
    @Test
    fun theDeviceSyncedIntoIsToldWhatArrivedAndFromWhom() = runBlocking {
        val laptop = newDevice("Laptop", clock = 1_786_000_000_000)
        val phone = newDevice("Phone", clock = 1_786_000_100_000)

        val announced = mutableListOf<Pair<String, MergeReport>>()
        val server = PeerServer(laptop.store, laptop.peers, laptop.identity) { peerName, taken ->
            announced += peerName to taken
        }
        servers += server
        val port = server.start(requestedPort = 0)
        val client = PeerClient(phone.store, phone.peers, phone.identity)
        clients += client
        client.pair("127.0.0.1", port, server.beginPairing())

        phone.store.createSubscription(draft("Disney", "2026-05-05"), 799)
        client.sync("127.0.0.1", port, laptop.id)

        assertEquals(1, announced.size, "the laptop was told exactly once")
        val (who, report) = announced.single()
        assertEquals("Phone", who, "and told which device it came from")
        assertEquals(2, report.received)
        assertEquals("2 changes from Phone.", report.describeIncoming(who))

        // A sync that brings nothing must not claim it did.
        client.sync("127.0.0.1", port, laptop.id)
        assertEquals(2, announced.size)
        assertTrue(!announced.last().second.changed, "nothing arrived the second time")
    }

    /**
     * Six digits is a million possibilities, and unlimited guesses over a LAN
     * exhaust that well inside the three minutes the window stays open. The code
     * being short was only ever defensible because it is short-lived and
     * single-use, neither of which helps against something trying them all.
     */
    @Test
    fun repeatedWrongCodesCloseThePairingWindow() = runBlocking {
        val laptop = newDevice("Laptop")
        val phone = newDevice("Phone")
        val server = PeerServer(laptop.store, laptop.peers, laptop.identity)
        servers += server
        val port = server.start(requestedPort = 0)
        val realCode = server.beginPairing()
        assertTrue(server.isPairingOpen)

        val client = PeerClient(phone.store, phone.peers, phone.identity)
        clients += client

        // A wrong code that is definitely not the real one.
        val wrong = if (realCode == "000000") "111111" else "000000"
        repeat(PeerServer.MAX_WRONG_ATTEMPTS) {
            assertFailsWith<PeerException> { client.pair("127.0.0.1", port, wrong) }
        }

        assertTrue(!server.isPairingOpen, "the window closed itself after repeated guesses")
        assertTrue(server.pairingWasRefused, "and recorded why, so the UI can explain it")

        // And the real code is now dead too — that is the point. A guesser who
        // happened to reach the right one on the next attempt must still fail.
        assertFailsWith<PeerException> { client.pair("127.0.0.1", port, realCode) }
        assertEquals(0, phone.peers.all().size, "nothing was paired")
    }

    /**
     * A human mistyping a code they can see must not be locked out on the first
     * slip, and getting it right afterwards has to work.
     */
    @Test
    fun aMistypedCodeStillLeavesRoomToCorrectIt() = runBlocking {
        val laptop = newDevice("Laptop")
        val phone = newDevice("Phone")
        val server = PeerServer(laptop.store, laptop.peers, laptop.identity)
        servers += server
        val port = server.start(requestedPort = 0)
        val realCode = server.beginPairing()
        val client = PeerClient(phone.store, phone.peers, phone.identity)
        clients += client

        val wrong = if (realCode == "000000") "111111" else "000000"
        assertFailsWith<PeerException> { client.pair("127.0.0.1", port, wrong) }
        assertTrue(server.isPairingOpen, "one slip is not an attack")

        val paired = client.pair("127.0.0.1", port, realCode)
        assertEquals(laptop.id, paired.deviceId)
        assertTrue(!server.pairingWasRefused, "a successful pair is not a refusal")
    }

    /**
     * The counter is only a defence if it survives concurrency. Ktor serves
     * requests in parallel, so fired all at once the guesses could each read the
     * count before any of them had incremented it — which is exactly how an
     * attacker would send them.
     */
    @Test
    fun parallelGuessesCannotOutrunTheAttemptLimit() = runBlocking {
        val laptop = newDevice("Laptop")
        val server = PeerServer(laptop.store, laptop.peers, laptop.identity)
        servers += server
        val port = server.start(requestedPort = 0)
        val realCode = server.beginPairing()

        val raw = HttpClient {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
        }
        try {
            // Fifty wrong codes at once, none of them the real one.
            val attempts = (1..50).map { n ->
                async {
                    raw.post("http://127.0.0.1:$port${PeerProtocol.PAIR_PATH}") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            PairRequest(
                                code = "9$n".padStart(6, '7'),
                                deviceId = "intruder00000$n".take(15),
                                deviceName = "Intruder",
                            )
                        )
                    }.status
                }
            }.awaitAll()

            assertTrue(
                attempts.all { it == HttpStatusCode.Forbidden },
                "every wrong guess was refused, none hit an unhandled error",
            )
            assertTrue(!server.isPairingOpen, "the window closed despite the guesses being parallel")
            // The real code went down with it.
            val afterwards = raw.post("http://127.0.0.1:$port${PeerProtocol.PAIR_PATH}") {
                contentType(ContentType.Application.Json)
                setBody(PairRequest(code = realCode, deviceId = "intruder000009", deviceName = "X"))
            }
            assertEquals(HttpStatusCode.Forbidden, afterwards.status)
        } finally {
            raw.close()
        }
    }

    /**
     * /pair used to be the one handler with no guard, so malformed JSON became a
     * bare 500 with an empty body that never reached `lastFailure`. Anything on
     * the network can post here.
     */
    @Test
    fun malformedPairingRequestsGetAnAnswerRatherThanABareFailure() = runBlocking {
        val laptop = newDevice("Laptop")
        val server = PeerServer(laptop.store, laptop.peers, laptop.identity)
        servers += server
        val port = server.start(requestedPort = 0)
        server.beginPairing()

        val raw = HttpClient { expectSuccess = false }
        try {
            val response = raw.post("http://127.0.0.1:$port${PeerProtocol.PAIR_PATH}") {
                contentType(ContentType.Application.Json)
                setBody("{ this is not json")
            }
            assertTrue(
                response.status.value >= 400,
                "a malformed body is refused rather than accepted",
            )
            assertTrue(
                response.bodyAsText().isNotBlank(),
                "and comes back with a body saying something, not an empty 500",
            )
            assertNotNull(server.lastFailure, "the failure was recorded so it is diagnosable")
            assertTrue(server.isPairingOpen, "a broken request is not a wrong guess")
        } finally {
            raw.close()
        }
    }

    /**
     * A peer that predates the accepted count still syncs; it just cannot say
     * how much it took. Decoding as 0 is the honest answer, not a wrong one.
     */
    @Test
    fun anOlderPeerThatOmitsTheAcceptedCountStillWorks() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val fromProtocolOne = """{"deviceId":"old","deviceName":"Old","protocol":1}"""

        val decoded = json.decodeFromString<SyncResponse>(fromProtocolOne)

        assertEquals(0, decoded.accepted)
        assertEquals("Old", decoded.deviceName)
        assertEquals(0, decoded.payload.recordCount, "an absent payload is an empty one")
    }
}


