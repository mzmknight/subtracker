package io.github.mzmknight.subtracker.data

import androidx.compose.ui.graphics.decodeToImageBitmap
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import io.github.mzmknight.subtracker.core.Logo

/**
 * The logo path on the JVM desktop, end to end.
 *
 * Worth its own suite because almost none of this is shared with Android: the
 * scaling is a separate `actual` built on ImageIO rather than BitmapFactory, and
 * it runs inside a jlink runtime that only contains the modules the build asked
 * for. Verifying the feature on a phone says nothing about whether it works in
 * the packaged Windows app.
 *
 * The server here is local, so nothing in this test reaches the internet or
 * depends on what a vendor happens to be serving today.
 */
class DesktopLogoTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop(0, 0) }
    }

    private fun pngOf(size: Int): ByteArray {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(229, 9, 20)
        graphics.fillRect(0, 0, size, size)
        graphics.color = Color.WHITE
        graphics.fillRect(size / 4, 0, size / 2, size)
        graphics.dispose()

        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun dimensionsOf(bytes: ByteArray): Pair<Int, Int> {
        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)), "not a decodable image")
        return image.width to image.height
    }

    // ------------------------------------------------------------- scaling

    @Test
    fun aLargeImageIsShrunkToTheTargetAndStaysDecodable() {
        val scaled = assertNotNull(scaleToPng(pngOf(512), Logo.TARGET_DIMENSION))

        val (width, height) = dimensionsOf(scaled)
        assertEquals(Logo.TARGET_DIMENSION, width)
        assertEquals(Logo.TARGET_DIMENSION, height)
        assertTrue(
            Logo.isWithinLimit(scaled),
            "a scaled logo must fit the sync budget, was ${scaled.size} bytes",
        )
    }

    @Test
    fun aSmallIconIsNotUpscaledIntoABlurrySquare() {
        val scaled = assertNotNull(scaleToPng(pngOf(32), Logo.TARGET_DIMENSION))
        assertEquals(32 to 32, dimensionsOf(scaled))
    }

    @Test
    fun thingsThatAreNotImagesComeBackAsNullRatherThanThrowing() {
        // The single most common real response from a guessed logo URL.
        assertEquals(null, scaleToPng("<!doctype html><html>Not found</html>".encodeToByteArray(), 144))
        assertEquals(null, scaleToPng(ByteArray(0), 144))
        assertEquals(null, scaleToPng(ByteArray(2048) { 0x7F }, 144))
    }

    @Test
    fun aStoredLogoDecodesForRenderingOnSkia() {
        // Avatar draws through decodeToImageBitmap, which on the desktop is Skia
        // rather than the Android decoder that produced these bytes on a phone —
        // and a logo fetched on the phone arrives here by sync. If the two ever
        // disagreed, every synced logo would silently fall back to a letter.
        val stored = Logo.encode(assertNotNull(scaleToPng(pngOf(256), Logo.TARGET_DIMENSION)))
        val bytes = assertNotNull(Logo.decode(stored))

        val bitmap = bytes.decodeToImageBitmap()
        assertEquals(Logo.TARGET_DIMENSION, bitmap.width)
        assertEquals(Logo.TARGET_DIMENSION, bitmap.height)
    }

    // ------------------------------------------------------------- fetching

    private fun serve(routes: Map<String, Pair<ByteArray?, String>>): Int {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                routes.forEach { (path, response) ->
                    val (bytes, contentType) = response
                    get(path) {
                        if (bytes == null) {
                            call.respondText("nope", ContentType.Text.Html)
                        } else {
                            call.respondBytes(bytes, ContentType.parse(contentType))
                        }
                    }
                }
            }
        }
        servers += server
        server.start(wait = false)
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @Test
    fun aLogoIsDownloadedScaledAndEncodedReadyToSync() = runBlocking {
        val port = serve(mapOf("/apple-touch-icon.png" to (pngOf(512) to "image/png")))

        val result = LogoFetcher(
            urlsFor = { listOf("http://127.0.0.1:$port/apple-touch-icon.png") },
        ).fetch("Netflix")

        val found = assertTrue(result is LogoFetcher.Result.Found, "expected a logo, got $result")
            .let { result as LogoFetcher.Result.Found }

        // What lands in the record must survive the round trip back to pixels,
        // because that is what every device does with it on the other end.
        val decoded = assertNotNull(Logo.decode(found.encoded))
        assertEquals(Logo.TARGET_DIMENSION to Logo.TARGET_DIMENSION, dimensionsOf(decoded))
        assertTrue(Logo.isWithinLimit(decoded))
    }

    @Test
    fun anHtmlErrorPageIsSkippedAndTheNextCandidateIsTried() = runBlocking {
        // A guessed URL very often returns 200 with an error page. That is bytes,
        // it is "successful", and it is not a logo — decoding is the real test.
        val port = serve(
            mapOf(
                "/apple-touch-icon.png" to (null to "text/html"),
                "/favicon.ico" to (pngOf(180) to "image/png"),
            )
        )

        val result = LogoFetcher(
            urlsFor = {
                listOf(
                    "http://127.0.0.1:$port/apple-touch-icon.png",
                    "http://127.0.0.1:$port/favicon.ico",
                )
            },
        ).fetch("Netflix")

        assertTrue(result is LogoFetcher.Result.Found, "should have fallen through, got $result")
    }

    @Test
    fun aDeadUrlIsSkippedRatherThanFailingTheWholeFetch() = runBlocking {
        val port = serve(mapOf("/favicon.ico" to (pngOf(120) to "image/png")))

        val result = LogoFetcher(
            urlsFor = {
                listOf(
                    // Nothing is listening on this path; it 404s.
                    "http://127.0.0.1:$port/missing.png",
                    "http://127.0.0.1:$port/favicon.ico",
                )
            },
        ).fetch("Netflix")

        assertTrue(result is LogoFetcher.Result.Found, "one bad URL must not end the search")
    }

    @Test
    fun nothingUsableGivesACleanNotFound() = runBlocking {
        val port = serve(mapOf("/apple-touch-icon.png" to (null to "text/html")))

        val result = LogoFetcher(
            urlsFor = { listOf("http://127.0.0.1:$port/apple-touch-icon.png") },
        ).fetch("Netflix")

        assertEquals(LogoFetcher.Result.NotFound, result)
    }

    @Test
    fun aNameWithNoGuessableDomainNeverReachesTheNetwork() = runBlocking {
        var asked = false
        val result = LogoFetcher(
            newClient = { error("the network must not be touched") },
            urlsFor = { asked = true; emptyList() },
        ).fetch("")

        assertEquals(LogoFetcher.Result.NoDomain, result)
        assertTrue(!asked, "no domain means no request at all")
    }
}
