package io.github.mzmknight.subtracker.sync

import android.os.Build

actual fun defaultDeviceName(): String {
    val model = Build.MODEL?.trim().orEmpty()
    val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
    return when {
        model.isBlank() -> "Android phone"
        // "Pixel 9" rather than "Google Pixel 9" — the model usually already
        // carries the brand.
        manufacturer.isNotBlank() && !model.startsWith(manufacturer, ignoreCase = true) ->
            "$manufacturer $model"
        else -> model
    }
}
