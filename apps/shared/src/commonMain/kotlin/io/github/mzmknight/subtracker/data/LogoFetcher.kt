package io.github.mzmknight.subtracker.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import io.github.mzmknight.subtracker.core.Logo

/**
 * Finds a subscription's logo on the internet.
 *
 * This is the only part of SubTracker that talks to anything other than the
 * user's own devices and their own server, so it runs on an explicit press
 * rather than on its own. The order of [Logo.candidateUrls] is the privacy
 * decision: the vendor's own domain is asked first, and a shared icon service
 * is reached only when the vendor publishes nothing usable.
 */
class LogoFetcher(
    private val newClient: () -> HttpClient = ::defaultClient,
    /**
     * Where to look, given a domain. Injectable so the whole path — download,
     * decode, resize, encode — can be run against a local server in a test
     * rather than against whatever netflix.com happens to be serving today.
     */
    private val urlsFor: (String) -> List<String> = Logo::candidateUrls,
) {

    sealed interface Result {
        /** Base64 PNG, ready to store in the record's `icon` column. */
        data class Found(val encoded: String) : Result
        data object NoDomain : Result
        data object NotFound : Result
        data class Failed(val message: String) : Result
    }

    suspend fun fetch(name: String, vendor: String = ""): Result {
        // Checked before the client is built: there is nothing to ask for, so
        // opening a connection pool to ask it would be pure waste.
        if (Logo.guessDomain(name, vendor) == null) return Result.NoDomain

        val client = newClient()
        return try {
            fetchWith(client, name, vendor)
        } finally {
            client.close()
        }
    }

    /**
     * Look up many at once, over a single connection pool.
     *
     * Used after an import, where every subscription arrives without a logo —
     * the file deliberately does not carry them. A fresh client per lookup would
     * throw away connection reuse exactly where it matters most.
     *
     * Runs a few at a time rather than all at once: a lookup that has to fall
     * through every candidate can take several seconds, so twenty in sequence is
     * minutes, while twenty in parallel is a burst that looks like abuse.
     */
    suspend fun fetchEach(
        subjects: List<Pair<String, String>>,
        onResult: suspend (index: Int, subject: Pair<String, String>, result: Result) -> Unit,
    ) {
        if (subjects.isEmpty()) return
        val client = newClient()
        try {
            var index = 0
            for (batch in subjects.chunked(CONCURRENCY)) {
                val start = index
                val results = coroutineScope {
                    batch.map { subject -> async { fetchWith(client, subject.first, subject.second) } }
                        .awaitAll()
                }
                results.forEachIndexed { offset, result ->
                    onResult(start + offset, batch[offset], result)
                }
                index += batch.size
            }
        } finally {
            client.close()
        }
    }

    private suspend fun fetchWith(client: HttpClient, name: String, vendor: String): Result {
        val domain = Logo.guessDomain(name, vendor) ?: return Result.NoDomain

        return try {
            for (url in urlsFor(domain)) {
                val bytes = runCatching { download(client, url) }.getOrNull() ?: continue

                // Decoding is the real test of whether this is a logo. A guessed
                // URL very often returns a 200 with an HTML error page, which is
                // bytes, is "successful", and is not an image.
                val png = scaleToPng(bytes, Logo.TARGET_DIMENSION) ?: continue
                if (!Logo.isWithinLimit(png)) continue

                return Result.Found(Logo.encode(png))
            }
            Result.NotFound
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Couldn't reach the network.")
        }
    }

    private suspend fun download(client: HttpClient, url: String): ByteArray? {
        val response: HttpResponse = client.get(url) {
            // Several vendors and CDNs refuse a request with no recognisable
            // user agent, which shows up as a 403 rather than as anything to do
            // with the icon.
            header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/128.0 Safari/537.36",
            )
            header("Accept", "image/png,image/*;q=0.8,*/*;q=0.5")
        }
        if (!response.status.isSuccess()) return null

        // Trust the declared length only to reject early; the body is capped
        // again after reading, because a server may not declare one at all.
        val declared = response.contentLength()
        if (declared != null && declared > Logo.MAX_DOWNLOAD_BYTES) return null

        val bytes: ByteArray = response.body()
        return if (bytes.size > Logo.MAX_DOWNLOAD_BYTES) null else bytes
    }

    private fun HttpResponse.contentLength(): Long? =
        headers["Content-Length"]?.toLongOrNull()

    companion object {
        /** Enough to keep a bulk lookup brisk without looking like a scrape. */
        private const val CONCURRENCY = 4

        private fun defaultClient(): HttpClient = HttpClient {
            // Vendors move their icons around constantly; without this the very
            // common 301 to www. is a failure rather than a logo.
            followRedirects = true
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 4_000
            }
        }
    }
}
