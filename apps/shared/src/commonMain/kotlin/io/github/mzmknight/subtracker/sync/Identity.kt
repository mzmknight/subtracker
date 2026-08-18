package io.github.mzmknight.subtracker.sync

import kotlin.random.Random
import io.github.mzmknight.subtracker.data.AppSettings

/**
 * Record IDs are minted on the device, not by the server — that is what lets a
 * subscription be created with no network at all.
 *
 * The format deliberately matches PocketBase's own: 15 characters of lowercase
 * alphanumeric. Client-supplied IDs then need no special handling server-side,
 * and a record created offline keeps the same identity forever once it syncs.
 */
object RecordId {

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val LENGTH = 15

    fun generate(random: Random = Random.Default): String = buildString(LENGTH) {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    /** Whether PocketBase will accept this as an id. */
    fun isValid(id: String): Boolean =
        id.length == LENGTH && id.all { it in 'a'..'z' || it in '0'..'9' }
}

/**
 * Who a given store or peer connection believes it is.
 *
 * An interface rather than a direct reference to [DeviceIdentity] because that
 * is a process-wide singleton: two devices simulated in one process would
 * otherwise share an id, and the tests that prove convergence would be testing
 * one device talking to itself.
 */
interface DeviceInfo {
    val id: String
    val name: String
}

/** Where the hybrid logical clock's state is kept between restarts. */
interface ClockStore {
    fun loadClockState(): Hlc?
    fun saveClockState(state: Hlc)
}

/**
 * This installation's identity. Stable across restarts, distinct per device, and
 * used both as the HLC tiebreak and as the name a peer sees when pairing.
 */
object DeviceIdentity : DeviceInfo, ClockStore {

    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_CLOCK_STATE = "clock_state"

    /**
     * 16 hex characters — a fixed width, which matters because it is the last
     * component of the HLC string and lexicographic sorting depends on every
     * component keeping its length.
     */
    override val id: String
        get() {
            AppSettings.store.get(KEY_DEVICE_ID)?.takeIf { it.length == 16 }?.let { return it }
            val fresh = buildString(16) {
                repeat(16) { append("0123456789abcdef"[Random.nextInt(16)]) }
            }
            AppSettings.store.put(KEY_DEVICE_ID, fresh)
            return fresh
        }

    /** Shown to the other device during pairing, so "which one is this?" is answerable. */
    override var name: String
        get() = AppSettings.store.get(KEY_DEVICE_NAME).orEmpty().ifBlank { defaultDeviceName() }
        set(value) = AppSettings.store.put(KEY_DEVICE_NAME, value.trim())

    /**
     * The clock must survive a restart. Without this a device that restarts
     * after its wall clock jumped backwards would issue timestamps that sort
     * before edits it already made, and lose its own writes on the next merge.
     */
    override fun loadClockState(): Hlc? = AppSettings.store.get(KEY_CLOCK_STATE)?.let(Hlc::parse)

    override fun saveClockState(state: Hlc) {
        AppSettings.store.put(KEY_CLOCK_STATE, state.encode())
    }
}
