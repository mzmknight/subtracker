package io.github.mzmknight.subtracker.core

/**
 * Colours for subscriptions, as hex strings.
 *
 * Deliberately free of any Compose type: this decides what colour a
 * subscription *is*, which is data that gets stored and synced, not how it is
 * painted. The UI parses these into Colors at the last moment.
 *
 * Every colour here has to stay legible against both the light and dark
 * palettes, because the avatar draws the name in this colour over a 18%-alpha
 * wash of it. That rules out near-whites and pale yellows, which is why a few
 * brands below are not their exact official shade.
 */
object BrandColours {

    /** Offered in the picker. Ordered by hue so the row reads as a spectrum. */
    val PALETTE = listOf(
        "#FF4D4D", // red
        "#FF7043", // orange
        "#D97757", // clay
        "#FFB454", // amber
        "#C9B458", // gold
        "#3DDC91", // emerald
        "#1DB954", // green
        "#2BC4B4", // teal
        "#5AA9FF", // sky
        "#4285F4", // blue
        "#7C6BFF", // indigo
        "#B388FF", // violet
        "#FF6B9D", // pink
        "#8FA3BC", // slate
    )

    /**
     * Recognised services, keyed by a substring of the normalised name.
     *
     * Matched by containment rather than equality so "YouTube Premium",
     * "Youtube TV" and plain "youtube" all land on the same red.
     */
    private val BRANDS = listOf(
        "youtube" to "#FF0033",
        "netflix" to "#E50914",
        "spotify" to "#1DB954",
        "anthropic" to "#D97757",
        "claude" to "#D97757",
        "openai" to "#10A37F",
        "chatgpt" to "#10A37F",
        "disney" to "#4B7BEC",
        "prime" to "#00A8E1",
        "amazon" to "#FF9900",
        "audible" to "#F8991C",
        "apple" to "#9AA0A6",
        "icloud" to "#3693F3",
        "google" to "#4285F4",
        "microsoft" to "#00A4EF",
        "xbox" to "#5CB85C",
        "playstation" to "#2E77D0",
        "nintendo" to "#E60012",
        "steam" to "#66C0F4",
        "twitch" to "#9146FF",
        "discord" to "#5865F2",
        "slack" to "#C86BD6",
        "notion" to "#9AA0A6",
        "figma" to "#F24E1E",
        "adobe" to "#EC1C24",
        "dropbox" to "#0061FF",
        "github" to "#7C8CF8",
        "reddit" to "#FF4500",
        "hulu" to "#1CE783",
        "paramount" to "#4B7BEC",
        "peacock" to "#FF7043",
        "hbo" to "#B388FF",
        "max" to "#B388FF",
        "nowtv" to "#2BC4B4",
        "britbox" to "#5AA9FF",
        "dazn" to "#C9B458",
        "sky" to "#5AA9FF",
        "virgin" to "#FF4D4D",
        "vodafone" to "#FF4D4D",
        "three" to "#7C6BFF",
        "nord" to "#4687FF",
        "proton" to "#7C6BFF",
        "express" to "#FF4D4D",
        "gym" to "#3DDC91",
        "fitness" to "#3DDC91",
        "peloton" to "#FF4D4D",
        "strava" to "#FC5200",
        "duolingo" to "#1DB954",
        "kindle" to "#FF9900",
        "patreon" to "#FF7043",
        "medium" to "#8FA3BC",
        "substack" to "#FF7043",
    )

    /**
     * The colour a subscription should get if the user never picks one.
     *
     * Falls back to a palette entry chosen from a hash of the name rather than
     * to a single default: without that, every subscription starts identical and
     * the composition chart is a wall of one colour. Hashing rather than
     * counting also means two devices that add "Netflix" independently agree on
     * its colour, so a sync does not shuffle the chart.
     */
    fun suggest(name: String, vendor: String = ""): String {
        val haystack = normalise("$name $vendor")
        if (haystack.isBlank()) return PALETTE[0]

        BRANDS.firstOrNull { (key, _) -> haystack.contains(key) }?.let { return it.second }

        return PALETTE[(hash(normalise(name)) % PALETTE.size).toInt()]
    }

    /** Whether [suggest] would recognise this as a known service. */
    fun isKnownBrand(name: String, vendor: String = ""): Boolean {
        val haystack = normalise("$name $vendor")
        return BRANDS.any { (key, _) -> haystack.contains(key) }
    }

    private fun normalise(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()

    /** FNV-1a, for a hash that is identical on every device and every platform. */
    private fun hash(value: String): Long {
        var result = 2166136261L
        for (ch in value) {
            result = result xor (ch.code.toLong() and 0xFF)
            result = (result * 16777619L) and 0xFFFFFFFFL
        }
        return result
    }
}
