package io.github.mzmknight.subtracker.sync

import kotlinx.serialization.Serializable

/**
 * What one device sends another.
 *
 * Only intent, and *all* of it including tombstones — sending only live records
 * would make deletions invisible to the peer, which is the same bug tombstones
 * exist to prevent. The whole payload is a few KB because charges are derived
 * and stay behind.
 */
@Serializable
data class SyncPayload(
    val deviceId: String = "",
    val deviceName: String = "",
    val protocol: Int = PROTOCOL_VERSION,
    val subscriptions: List<SubscriptionRecord> = emptyList(),
    val prices: List<PricePeriodRecord> = emptyList(),
    val overrides: List<ChargeOverrideRecord> = emptyList(),
    /**
     * User-level settings. Absent from a protocol 1 peer's payload, which
     * decodes as an empty list and simply means "this peer has no opinion about
     * settings" — so an old device and a new one still sync everything else.
     */
    val settings: List<SettingRecord> = emptyList(),
) {
    val recordCount: Int
        get() = subscriptions.size + prices.size + overrides.size + settings.size

    companion object {
        /**
         * 2 added [settings]. Bumped rather than left alone because the field is
         * additive and older peers ignore it: the version is what lets a device
         * *know* a peer will drop its settings rather than guessing from an
         * empty list, which is also what an up-to-date peer sends.
         */
        const val PROTOCOL_VERSION = 2
    }
}

data class MergeReport(
    val subscriptionsChanged: Int = 0,
    val pricesChanged: Int = 0,
    val overridesChanged: Int = 0,
    val settingsChanged: Int = 0,
    val sentCount: Int = 0,
) {
    val received: Int
        get() = subscriptionsChanged + pricesChanged + overridesChanged + settingsChanged
    val changed: Boolean get() = received > 0

    fun describe(): String = when {
        received == 0 && sentCount == 0 -> "Already up to date"
        received == 0 -> "Sent $sentCount change${plural(sentCount)}"
        sentCount == 0 -> "Received $received change${plural(received)}"
        else -> "Received $received, sent $sentCount"
    }

    /**
     * Phrased for the device that was synced *into*.
     *
     * It did not ask for any of this, so the message has to say where it came
     * from — "Received 3 changes" on a phone you never touched is a mystery
     * rather than an explanation.
     */
    fun describeIncoming(peerName: String): String {
        val who = peerName.ifBlank { "another device" }
        return "$received change${plural(received)} from $who."
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
