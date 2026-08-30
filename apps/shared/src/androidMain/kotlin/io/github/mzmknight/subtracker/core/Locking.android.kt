package io.github.mzmknight.subtracker.core

actual class Lock {
    private val monitor = Any()
    actual fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}
