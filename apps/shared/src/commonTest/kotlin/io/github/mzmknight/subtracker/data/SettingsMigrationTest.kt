package io.github.mzmknight.subtracker.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.mzmknight.subtracker.db.SubTrackerDatabase
import io.github.mzmknight.subtracker.sync.ClockStore
import io.github.mzmknight.subtracker.sync.DeviceInfo
import io.github.mzmknight.subtracker.sync.Hlc

/**
 * Upgrading a database that already has the user's data in it.
 *
 * Schema version 4 added the `setting` table. The version-3 schema is written
 * out by hand below rather than generated, because generating it from today's
 * definitions would test the migration against itself and pass no matter what:
 * the whole question is whether a database created by the *previous release*
 * still opens, so the previous release's shape has to be stated literally.
 */
class SettingsMigrationTest {

    private val drivers = mutableListOf<JdbcSqliteDriver>()

    @AfterTest
    fun tearDown() {
        drivers.forEach { it.close() }
    }

    private class TestIdentity(override val id: String, override val name: String) :
        DeviceInfo, ClockStore {
        private var state: Hlc? = null
        override fun loadClockState(): Hlc? = state
        override fun saveClockState(state: Hlc) {
            this.state = state
        }
    }

    /** Exactly what shipped as schema version 3 — no `setting` table. */
    private fun createVersion3(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            """
            CREATE TABLE subscription (
                id             TEXT NOT NULL PRIMARY KEY,
                updated_at     TEXT NOT NULL,
                deleted        INTEGER NOT NULL DEFAULT 0,
                name           TEXT NOT NULL,
                vendor         TEXT NOT NULL DEFAULT '',
                category       TEXT NOT NULL DEFAULT 'other',
                currency       TEXT NOT NULL DEFAULT 'GBP',
                cycle_unit     TEXT NOT NULL,
                cycle_count    INTEGER NOT NULL,
                anchor_date    TEXT NOT NULL,
                status         TEXT NOT NULL,
                end_date       TEXT,
                trial_end      TEXT,
                payment_method TEXT NOT NULL DEFAULT '',
                colour         TEXT NOT NULL DEFAULT '',
                icon           TEXT NOT NULL DEFAULT '',
                notes          TEXT NOT NULL DEFAULT '',
                notify_mode        TEXT NOT NULL DEFAULT '',
                notify_days_before INTEGER NOT NULL DEFAULT 3,
                notify_minute      INTEGER NOT NULL DEFAULT 540
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE INDEX subscription_updated ON subscription(updated_at)", 0)
        driver.execute(
            null,
            """
            CREATE TABLE price_period (
                id              TEXT NOT NULL PRIMARY KEY,
                updated_at      TEXT NOT NULL,
                deleted         INTEGER NOT NULL DEFAULT 0,
                subscription_id TEXT NOT NULL,
                amount_minor    INTEGER NOT NULL,
                effective_from  TEXT NOT NULL,
                note            TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "CREATE INDEX price_period_subscription ON price_period(subscription_id)",
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE charge_override (
                id              TEXT NOT NULL PRIMARY KEY,
                updated_at      TEXT NOT NULL,
                deleted         INTEGER NOT NULL DEFAULT 0,
                subscription_id TEXT NOT NULL,
                due_date        TEXT NOT NULL,
                kind            TEXT NOT NULL,
                amount_minor    INTEGER
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "CREATE INDEX charge_override_subscription ON charge_override(subscription_id)",
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE sync_state (
                peer_id      TEXT NOT NULL PRIMARY KEY,
                peer_name    TEXT NOT NULL DEFAULT '',
                last_sync_at INTEGER NOT NULL DEFAULT 0,
                secret       TEXT NOT NULL DEFAULT '',
                host         TEXT NOT NULL DEFAULT '',
                port         INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            0,
        )
    }

    @Test
    fun aVersion3DatabaseUpgradesWithoutLosingAnything() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        drivers += driver
        createVersion3(driver)

        // A subscription and its price, as the previous release would have left
        // them — including a per-subscription reminder rule, which already
        // synced and must not be disturbed by settings starting to.
        driver.execute(
            null,
            """
            INSERT INTO subscription(
                id, updated_at, deleted, name, vendor, category, currency,
                cycle_unit, cycle_count, anchor_date, status,
                payment_method, colour, icon, notes,
                notify_mode, notify_days_before, notify_minute
            ) VALUES (
                's1', '0000000000000001-00001-devicexxxxxxxxxx', 0, 'Netflix', 'netflix',
                'entertainment', 'GBP', 'month', 1, '2026-01-15', 'active',
                'Amex', '#E50914', 'logo-bytes', 'Standard', 'off', 3, 540
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO price_period(id, updated_at, deleted, subscription_id, amount_minor, effective_from)
            VALUES ('p1', '0000000000000001-00002-devicexxxxxxxxxx', 0, 's1', 1099, '2026-01-15')
            """.trimIndent(),
            0,
        )

        SubTrackerDatabase.Schema.migrate(driver, 3, 4)

        val identity = TestIdentity("devicexxxxxxxxxx", "Device")
        val store = LocalStore(SubTrackerDatabase(driver), identity, identity)

        val subscription = store.subscriptions().single()
        assertEquals("Netflix", subscription.name)
        assertEquals("logo-bytes", subscription.icon, "the logo survived")
        assertEquals("off", subscription.notifyMode, "and so did its reminder rule")

        // The new table exists and is empty, which is what makes the carry-across
        // in AppSettings.useSyncedStore fire on first launch after the upgrade.
        assertTrue(store.allSettings().isEmpty())
        assertNull(store.setting("home_currency"))

        store.putSetting("home_currency", "EUR")
        assertEquals("EUR", store.setting("home_currency"))
        assertEquals(1, store.exportAll().settings.size)
    }
}
