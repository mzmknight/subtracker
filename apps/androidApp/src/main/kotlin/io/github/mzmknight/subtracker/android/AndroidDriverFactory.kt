package io.github.mzmknight.subtracker.android

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.mzmknight.subtracker.db.DriverFactory
import io.github.mzmknight.subtracker.db.SubTrackerDatabase

/**
 * App-private database, removed on uninstall. AndroidSqliteDriver handles
 * creating and migrating the schema itself.
 */
class AndroidDriverFactory(private val context: Context) : DriverFactory {
    override fun create(): SqlDriver =
        AndroidSqliteDriver(
            schema = SubTrackerDatabase.Schema,
            context = context,
            name = "subtracker.db",
        )
}
