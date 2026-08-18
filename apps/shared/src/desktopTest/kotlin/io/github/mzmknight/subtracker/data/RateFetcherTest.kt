package io.github.mzmknight.subtracker.data

import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import io.github.mzmknight.subtracker.core.Rates

/**
 * The rate download, against a local stand-in for the API.
 *
 * The inversion is the part worth testing. The service answers "how much foreign
 * currency one pound buys" and the app stores the opposite; getting that
 * backwards produces figures that are wrong by the square of the rate and still
 * look like perfectly ordinary numbers.
 */
class RateFetcherTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop(0, 0) }
    }

    private fun fetcherServing(json: String): RateFetcher {
        val server = embeddedServer(CIO, port = 0) {
            routing { get("/latest") { call.respondText(json, ContentType.Application.Json) } }
        }
        servers += server
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        return RateFetcher(endpoint = "http://127.0.0.1:$port/latest")
    }

    /** The real response shape, copied from the live service. */
    private val body = """
        {"amount":1.0,"base":"GBP","date":"2026-08-11",
         "rates":{"AUD":1.911,"CAD":1.8815,"CHF":1.0939,"EUR":1.1698,
                  "JPY":214.92,"SEK":12.8254,"USD":1.35}}
    """.trimIndent()

    @Test
    fun ratesAreInvertedIntoUnitsOfHomePerForeignUnit() = runBlocking {
        val result = fetcherServing(body).fetch("GBP", listOf("USD", "EUR"))

        assertTrue(result is RateFetcher.Result.Fetched, "got $result")
        val fetched = result as RateFetcher.Result.Fetched

        // The service says 1 GBP buys 1.35 USD, so 1 USD is 1/1.35 = 0.7407 GBP.
        assertTrue(abs(fetched.rates.getValue("USD") - 0.740740) < 1e-5, "USD was ${fetched.rates["USD"]}")
        assertTrue(abs(fetched.rates.getValue("EUR") - 0.854847) < 1e-5, "EUR was ${fetched.rates["EUR"]}")
        assertEquals("2026-08-11", fetched.asOf)
    }

    @Test
    fun aConvertedAmountMatchesTheHandCalculation() = runBlocking {
        val result = fetcherServing(body).fetch("GBP", listOf("USD")) as RateFetcher.Result.Fetched
        val rates = Rates("GBP").withFetched(result.rates, 1L)

        // $23.76 at 0.7407 is £17.60.
        assertEquals(1760, rates.toHome(2376, "USD"))
    }

    @Test
    fun aManualRateSurvivesARefresh() {
        // The whole point of pinning one: an automatic update must never
        // overwrite a number the user entered deliberately.
        val pinned = Rates("GBP").with("USD", 0.50).withFetched(mapOf("USD" to 0.74), 1L)

        assertEquals(0.50, pinned.rateFor("USD"))
        assertTrue(pinned.isManual("USD"))
        // Removing it falls back to the downloaded one rather than to nothing.
        assertEquals(0.74, pinned.without("USD").rateFor("USD"))
        assertTrue(!pinned.without("USD").isManual("USD"))
    }

    @Test
    fun aZeroRateIsDroppedRatherThanInvertedIntoInfinity() = runBlocking {
        val result = fetcherServing(
            """{"base":"GBP","date":"2026-08-11","rates":{"USD":0,"EUR":1.17}}""",
        ).fetch("GBP", listOf("USD", "EUR")) as RateFetcher.Result.Fetched

        assertTrue("USD" !in result.rates, "a zero rate inverts to infinity and wrecks every total")
        assertTrue("EUR" in result.rates)
    }

    @Test
    fun nothingIsRequestedWhenEverythingIsAlreadyHome() = runBlocking {
        val result = RateFetcher(newClient = { error("must not open a connection") })
            .fetch("GBP", listOf("GBP", "GBP"))
        assertEquals(RateFetcher.Result.NothingToFetch, result)
    }

    @Test
    fun aBrokenResponseFailsCleanlyRatherThanThrowing() = runBlocking {
        val result = fetcherServing("""{"oops":true}""").fetch("GBP", listOf("USD"))
        assertTrue(result is RateFetcher.Result.Failed, "got $result")
    }

    @Test
    fun onlyCurrenciesTheAppCanShowAreAskedFor() {
        val supported = RateFetcher().supported(listOf("USD", "XYZ", "EUR", "DOGE"))
        assertEquals(listOf("USD", "EUR"), supported)
    }
}
