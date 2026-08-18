package io.github.mzmknight.subtracker.sync

import java.net.InetAddress

actual fun defaultDeviceName(): String {
    System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }?.let { return it }
    System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }?.let { return it }
    return runCatching { InetAddress.getLocalHost().hostName }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: "This PC"
}
