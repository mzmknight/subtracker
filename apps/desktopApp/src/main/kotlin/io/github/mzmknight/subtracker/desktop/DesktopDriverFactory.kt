package io.github.mzmknight.subtracker.desktop

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.mzmknight.subtracker.db.DriverFactory
import io.github.mzmknight.subtracker.db.SubTrackerDatabase

/**
 * SQLite in whichever data directory [AppPaths] resolved — beside the app when
 * portable, `%LOCALAPPDATA%\SubTracker` when installed.
 *
 * Unlike AndroidSqliteDriver, JdbcSqliteDriver does nothing about schema
 * creation or migration, so this tracks the schema version in SQLite's
 * `user_version` pragma and acts on it.
 *
 * That exists because of a real bug: a column was renamed while an older
 * database file was still on disk, and because the file existed the schema was
 * never re-created. Every sync then failed with "no such column", reported as
 * an opaque 500 to the other device.
 */
class DesktopDriverFactory : DriverFactory {

    override fun create(): SqlDriver {
        val file = AppPaths.databaseFile
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        val target = SubTrackerDatabase.Schema.version
        val onDisk = readVersion(driver)

        when {
            onDisk == 0L && !hasTables(driver) -> {
                SubTrackerDatabase.Schema.create(driver)
                writeVersion(driver, target)
            }

            onDisk < target -> {
                SubTrackerDatabase.Schema.migrate(driver, onDisk, target)
                writeVersion(driver, target)
            }

            onDisk > target -> {
                driver.close()
                error(
                    "This database was written by a newer version of SubTracker " +
                        "(schema $onDisk, this build understands $target). Refusing to open it " +
                        "rather than risk corrupting your data — update the app instead."
                )
            }
        }

        return driver
    }

    private fun hasTables(driver: SqlDriver): Boolean = driver.executeQuery(
        identifier = null,
        sql = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'subscription'",
        mapper = { cursor ->
            QueryResult.Value(if (cursor.next().value) (cursor.getLong(0) ?: 0L) > 0L else false)
        },
        parameters = 0,
    ).value

    private fun readVersion(driver: SqlDriver): Long = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        },
        parameters = 0,
    ).value

    private fun writeVersion(driver: SqlDriver, version: Long) {
        driver.execute(null, "PRAGMA user_version = $version", 0)
    }
}
