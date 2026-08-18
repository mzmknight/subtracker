package io.github.mzmknight.subtracker.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-supplied SQLite driver. Android needs a Context, the desktop needs a
 * file path, so each app module provides one at startup — the same pattern the
 * settings and file stores already use.
 */
interface DriverFactory {
    fun create(): SqlDriver
}

object Database {

    private var factory: DriverFactory? = null
    private var instance: SubTrackerDatabase? = null

    fun install(driverFactory: DriverFactory) {
        factory = driverFactory
        instance = null
    }

    /** Installed for tests with an in-memory driver. */
    fun installDriver(driver: SqlDriver) {
        SubTrackerDatabase.Schema.create(driver)
        instance = SubTrackerDatabase(driver)
    }

    fun get(): SubTrackerDatabase {
        instance?.let { return it }
        val created = factory?.create()
            ?: error("No database driver installed — call Database.install() before use")
        val database = SubTrackerDatabase(created)
        instance = database
        return database
    }

    fun reset() {
        instance = null
    }
}
