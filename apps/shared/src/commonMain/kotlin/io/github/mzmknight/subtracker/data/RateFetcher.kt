package io.github.mzmknight.subtracker.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.mzmknight.subtracker.core.Money

/**
 * Downloads exchange rates.
 *
 * Frankfurter: European Central Bank reference rates, no account, no key, and a
 * single request covers every currency the app offers — a couple of hundred
 * bytes. The alternatives either wanted an API key, which would mean the user
 * signing up for something before their totals were correct, or returned three
 * hundred currencies and a pile of cryptocurrencies to get at eight numbers.
 *
 * What this leaks is one request naming a set of currency codes. That is a far
 * smaller thing than the logo lookup gives away, and it buys totals that are
 * right without anyone having to know today's rate.
 */
class RateFetcher(
    private val newClient: () -> HttpClient = ::defaultClient,
    /** Injectable so the parse-and-invert can be tested against a local server. */
    private val endpoint: String = ENDPOINT,
) {

    sealed interface Result {
        data class Fetched(val rates: Map<String, Double>, val asOf: String) : Result
        data object NothingToFetch : Result
        data class Failed(val message: String) : Result
    }

    /**
     * Rates for [wanted], expressed as units of [home] per one unit of each.
     *
     * The API answers the other way round — how much foreign currency one unit
     * of home buys — so every figure is inverted here. Requesting `from=USD`
     * once per currency would avoid that, but it is one request per currency
     * instead of one in total.
     */
    suspend fun fetch(home: String, wanted: Collection<String>): Result {
        val symbols = wanted.distinct().filter { it != home }.sorted()
        if (symbols.isEmpty()) return Result.NothingToFetch

        val client = newClient()
        return try {
            val url = "$endpoint?from=$home&to=${symbols.joinToString(",")}"
            val response: HttpResponse = client.get(url)
            if (!response.status.isSuccess()) {
                return Result.Failed("The rate service answered ${response.status.value}.")
            }

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val asOf = body["date"]?.jsonPrimitive?.content.orEmpty()
            val rates = body["rates"]?.jsonObject
                ?: return Result.Failed("The rate service sent something unexpected.")

            val inverted = rates.mapNotNull { (code, value) ->
                val perHome = value.jsonPrimitive.content.toDoubleOrNull()
                // A zero or missing rate would invert to infinity and erase or
                // explode a total, so it is dropped rather than stored.
                if (perHome == null || perHome <= 0.0) null else code.uppercase() to 1.0 / perHome
            }.toMap()

            if (inverted.isEmpty()) {
                Result.Failed("No usable rates came back.")
            } else {
                Result.Fetched(inverted, asOf)
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Couldn't reach the rate service.")
        } finally {
            client.close()
        }
    }

    /** Currencies worth asking about — the ones the app can actually display. */
    fun supported(currencies: Collection<String>): List<String> =
        currencies.distinct().filter { it in Money.KNOWN_CURRENCIES }

    companion object {
        const val ENDPOINT = "https://api.frankfurter.app/latest"

        /** Rates move slowly; refreshing more often than this is noise. */
        const val REFRESH_AFTER_MILLIS = 12L * 60 * 60 * 1000

        private fun defaultClient(): HttpClient = HttpClient {
            followRedirects = true
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
            }
        }
    }
}
