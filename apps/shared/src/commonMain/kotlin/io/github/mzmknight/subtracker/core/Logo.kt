package io.github.mzmknight.subtracker.core

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A subscription's logo, as stored in the `icon` column.
 *
 * The image itself lives in the record as base64 rather than as a URL, and that
 * is deliberate: the record is what syncs, so a logo fetched once on the phone
 * appears on the laptop without the laptop ever going near the internet — and
 * keeps working if the vendor later moves or removes the file.
 *
 * The cost is payload size, which is why [MAX_STORED_BYTES] is small and
 * enforced. Every logo travels in full on every sync.
 */
object Logo {

    /**
     * Ceiling on the decoded image, chosen against the sync payload rather than
     * against image quality: fifty subscriptions at this size is about 1 MB over
     * the wire, which a LAN sync swallows and a phone hotspot still tolerates.
     */
    const val MAX_STORED_BYTES = 24 * 1024

    /** Logos render at 48.dp at most, so this is already generous on a 3x screen. */
    const val TARGET_DIMENSION = 144

    /** Refuse anything absurd before it is even decoded. */
    const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024

    @OptIn(ExperimentalEncodingApi::class)
    fun encode(bytes: ByteArray): String = Base64.encode(bytes)

    /** Null rather than throwing: a corrupt or half-synced value must not crash a list. */
    @OptIn(ExperimentalEncodingApi::class)
    fun decode(stored: String): ByteArray? {
        if (stored.isBlank()) return null
        return runCatching { Base64.decode(stored) }.getOrNull()
    }

    fun isWithinLimit(bytes: ByteArray): Boolean = bytes.size <= MAX_STORED_BYTES

    /**
     * Where to look for a vendor's own icon.
     *
     * The vendor's own domain is tried first for a privacy reason, not a quality
     * one: asking netflix.com for its icon tells Netflix you were interested in
     * Netflix, which it already knows. Asking an aggregator instead tells a
     * third party your entire subscription list.
     */
    fun candidateUrls(domain: String): List<String> = listOf(
        "https://$domain/apple-touch-icon.png",
        "https://$domain/apple-touch-icon-precomposed.png",
        "https://www.$domain/apple-touch-icon.png",
        // Shared icon services, reached only when the vendor publishes nothing
        // usable at a predictable path. Both leak the lookup, so the vendor's own
        // domain is always tried first.
        //
        // DuckDuckGo comes first as the more privacy-respecting of the two, but
        // it cannot be relied on alone: it returns a PNG for some domains and a
        // true BMP-encoded ICO for others — amazon.com and netflix.com among
        // them — and no platform here can decode an ICO, so those simply failed.
        // Google's always answers PNG, which is why it is the backstop.
        "https://icons.duckduckgo.com/ip3/$domain.ico",
        "https://www.google.com/s2/favicons?domain=$domain&sz=$TARGET_DIMENSION",
    )

    /**
     * Best guess at the vendor's domain.
     *
     * Known services are mapped explicitly because their product names are not
     * their domains — "YouTube Premium" is not youtubepremium.com, and guessing
     * that way fetches nothing or, worse, someone else's logo.
     */
    fun guessDomain(name: String, vendor: String = ""): String? {
        val haystack = normalise("$name $vendor")
        if (haystack.isBlank()) return null

        DOMAINS.firstOrNull { (key, _) -> haystack.contains(key) }?.let { return it.second }

        // Unknown service: the first word is the best available guess. Anything
        // cleverer here is a guess dressed up as a fact.
        val firstWord = normalise(name).split(' ').firstOrNull { it.isNotBlank() } ?: return null
        if (firstWord.length < 3) return null
        return "$firstWord.com"
    }

    private val DOMAINS = listOf(
        "youtube" to "youtube.com",
        "netflix" to "netflix.com",
        "spotify" to "spotify.com",
        "anthropic" to "anthropic.com",
        "claude" to "claude.ai",
        "openai" to "openai.com",
        "chatgpt" to "openai.com",
        "disney" to "disneyplus.com",
        // Before the bare "prime"/"amazon" entries: Prime Video has its own site
        // and its own logo, and amazon.com would hand back the shopping one.
        "prime video" to "primevideo.com",
        "primevideo" to "primevideo.com",
        "prime" to "amazon.com",
        "amazon" to "amazon.com",
        "audible" to "audible.com",
        "icloud" to "icloud.com",
        "apple" to "apple.com",
        "google" to "google.com",
        "microsoft" to "microsoft.com",
        "xbox" to "xbox.com",
        "playstation" to "playstation.com",
        "nintendo" to "nintendo.com",
        "steam" to "steampowered.com",
        "twitch" to "twitch.tv",
        "discord" to "discord.com",
        "slack" to "slack.com",
        "notion" to "notion.so",
        "figma" to "figma.com",
        "adobe" to "adobe.com",
        "dropbox" to "dropbox.com",
        "github" to "github.com",
        "reddit" to "reddit.com",
        "hulu" to "hulu.com",
        "paramount" to "paramountplus.com",
        "peacock" to "peacocktv.com",
        "nowtv" to "nowtv.com",
        "britbox" to "britbox.com",
        "dazn" to "dazn.com",
        "vodafone" to "vodafone.com",
        "nord" to "nordvpn.com",
        "proton" to "proton.me",
        "peloton" to "onepeloton.com",
        "strava" to "strava.com",
        "duolingo" to "duolingo.com",
        "patreon" to "patreon.com",
        "medium" to "medium.com",
        "substack" to "substack.com",
    )

    private fun normalise(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()
}
