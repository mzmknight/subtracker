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
}


