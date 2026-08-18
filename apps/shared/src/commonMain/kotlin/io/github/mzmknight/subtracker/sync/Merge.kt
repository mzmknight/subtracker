package io.github.mzmknight.subtracker.sync

/**
 * Anything that travels between devices.
 *
 * Only *intent* is syncable: subscriptions, price periods, and the overrides you
 * apply to individual charges. Generated charges are derived from those and are
 * recomputed on each device, so they never cross the wire.
 */
interface Syncable {
    val id: String

    /** Encoded [Hlc]. Lexicographic order is causal order. */
    val updatedAt: String

    /**
     * Tombstone. Records are never actually removed from the synced set, because
     * "absent" and "deleted" are indistinguishable during a merge — without this,
     * deleting on your phone gets undone by your PC's stale copy on next sync.
     */
    val deleted: Boolean
}

data class MergeResult<T : Syncable>(
    val merged: List<T>,
    val incoming: List<T>,
    val outgoing: List<T>,
) {
    val changed: Boolean get() = incoming.isNotEmpty()

    /** Everything still alive, for the UI. */
    val live: List<T> get() = merged.filterNot { it.deleted }
}

object Merge {

    /**
     * Last-write-wins per record, by (millis, counter, deviceId).
     *
     * This is an LWW-element-set: commutative, associative and idempotent, so
     * devices converge no matter what order they sync in or how many times.
     * Adequate here because records are small, independent, and concurrent edits
     * to the *same* subscription from two devices are rare — a CRDT with
     * per-field merging would buy nothing but complexity.
     */
    fun <T : Syncable> merge(local: List<T>, remote: List<T>): MergeResult<T> {
        val byId = LinkedHashMap<String, T>(local.size + remote.size)
        for (record in local) byId[record.id] = record

        val incoming = mutableListOf<T>()
        for (candidate in remote) {
            val existing = byId[candidate.id]
            if (existing == null || Hlc.compareEncoded(candidate.updatedAt, existing.updatedAt) > 0) {
                byId[candidate.id] = candidate
                incoming += candidate
            }
        }

        // What the peer is missing or has an older copy of.
        val remoteById = remote.associateBy { it.id }
        val outgoing = local.filter { mine ->
            val theirs = remoteById[mine.id]
            theirs == null || Hlc.compareEncoded(mine.updatedAt, theirs.updatedAt) > 0
        }

        return MergeResult(byId.values.toList(), incoming, outgoing)
    }

    /**
     * Highest timestamp anywhere in a set, so the local clock can be advanced
     * past everything just learned. Skipping this lets a device with a slow
     * clock lose every subsequent merge.
     */
    fun highestTimestamp(records: List<Syncable>): Hlc? =
        records.mapNotNull { Hlc.parse(it.updatedAt) }.maxOrNull()
}
