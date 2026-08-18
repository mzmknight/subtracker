package io.github.mzmknight.subtracker.sync

/**
 * Deterministic ids for charge overrides.
 *
 * Charges are derived, so each device mints its own charge rows with their own
 * ids — there is no shared charge identity to hang an override off. If overrides
 * used random ids, your phone skipping the 15 August charge and your PC skipping
 * the same charge would produce two different rows, both surviving the merge,
 * both claiming the same charge.
 *
 * Deriving the id from (subscription, due_date) makes those the *same* record, so
 * ordinary last-write-wins settles it. Same input, same id, on every device and
 * forever.
 *
 * FNV-1a rather than a real hash because this is an identity function, not a
 * security boundary: it needs to be stable, fast, and available in common Kotlin
 * with no dependency.
 */
object OverrideId {

    private const val FNV_OFFSET = -3750763034362895579L // 14695981039346656037 unsigned
    private const val FNV_PRIME = 1099511628211L
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val LENGTH = 15

    private fun hash(seed: Long, text: String): Long {
        var value = seed
        for (char in text) {
            value = value xor char.code.toLong()
            value *= FNV_PRIME
        }
        return value
    }

    /** 15 lowercase alphanumerics, matching what PocketBase accepts as an id. */
    fun forCharge(subscriptionId: String, dueDate: String): String {
        val payload = "$subscriptionId|$dueDate"
        // Two independent passes, so 15 base-36 characters (~77 bits) are filled
        // with real entropy rather than the low bits of a single 64-bit hash.
        var high = hash(FNV_OFFSET, payload)
        var low = hash(FNV_OFFSET xor 0x5bf03635, "override:$payload")

        return buildString(LENGTH) {
            repeat(LENGTH) { index ->
                val source = if (index % 2 == 0) high else low
                append(ALPHABET[((source ushr 8) % ALPHABET.length).toInt().let { if (it < 0) it + ALPHABET.length else it }])
                if (index % 2 == 0) high = high * FNV_PRIME + 1 else low = low * FNV_PRIME + 1
            }
        }
    }
}
