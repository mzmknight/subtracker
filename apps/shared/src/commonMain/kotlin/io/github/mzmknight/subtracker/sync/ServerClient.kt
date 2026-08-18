package io.github.mzmknight.subtracker.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.mzmknight.subtracker.data.AppSettings
import io.github.mzmknight.subtracker.data.LocalStore

/**
 * Syncing with the PocketBase instance.
 *
 * Deliberately the *same* exchange as with another device: send everything,
 * merge what comes back. The server is a peer that happens to always be on —
 * it holds no authority the devices don't, which is what makes "the server was
 * down, fix it and let it catch up" an ordinary merge rather than a special case.
 *
 * The only difference from [PeerClient] is authentication: PocketBase has real
 * user accounts, so this signs in rather than exchanging a pairing code.
 */
class ServerClient(private val store: LocalStore) {

    @Serializable
    private data class AuthResponse(val token: String = "")

    @Serializable
    private data class ServerSyncResponse(
        val deviceId: String = "",
        val deviceName: String = "",
        val protocol: Int = 1,
        val payload: SyncPayload = SyncPayload(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 6_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    private fun base(): String {
        val url = AppSettings.baseUrl
        if (url.isBlank()) throw PeerException("No server address saved yet.")
        return url.trimEnd('/')
    }

    /** Signs in and stores the token, so later syncs are one request. */
    suspend fun signIn(baseUrl: String, email: String, password: String) = guarded {
        val trimmed = baseUrl.trim().trimEnd('/')
        val response = client.post("$trimmed/api/collections/users/auth-with-password") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("identity", email.trim())
                    put("password", password)
                }
            )
        }
        if (response.status == HttpStatusCode.BadRequest) {
            throw PeerException("That email or password wasn't accepted.")
        }
        if (!response.status.isSuccess()) {
            throw PeerException("The server refused the sign-in (${response.status.value}).")
        }
        val auth: AuthResponse = response.body()
        AppSettings.baseUrl = trimmed
        AppSettings.token = auth.token
        AppSettings.email = email.trim()
    }

    suspend fun sync(): MergeReport = guarded {
        val token = AppSettings.token
        if (token.isBlank()) throw PeerException("Sign in to the server first.")

        val response = client.post("${base()}/api/subtracker/sync") {
            contentType(ContentType.Application.Json)
            header("Authorization", token)
            setBody(store.exportAll())
        }

        if (response.status == HttpStatusCode.Unauthorized ||
            response.status == HttpStatusCode.Forbidden
        ) {
            throw PeerException("The server sign-in has expired. Sign in again.")
        }
        if (!response.status.isSuccess()) {
            throw PeerException("The server rejected the sync (${response.status.value}).")
        }

        val reply: ServerSyncResponse = response.body()
        store.applyIncoming(reply.payload)
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: PeerException) {
        throw e
    } catch (e: Exception) {
        throw PeerException(
            "Couldn't reach the server. Check it's running and Tailscale is connected."
        )
    }

    fun close() = client.close()
}
