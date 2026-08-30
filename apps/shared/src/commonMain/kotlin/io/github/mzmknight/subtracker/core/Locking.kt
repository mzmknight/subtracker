package io.github.mzmknight.subtracker.core

/**
 * Plain mutual exclusion, for the few places where two threads genuinely meet.
 *
 * Almost nothing in this app needs one: the UI is single-threaded and the
 * database has its own transactions. The exceptions are the hybrid logical
 * clock and the pairing window, both of which are touched by the Compose scope
 * *and* by Ktor handler threads serving a peer that started the conversation.
 *
 * An expect/actual rather than a coroutine `Mutex` because the call sites are
 * ordinary functions, not suspending ones — issuing a timestamp cannot become
 * suspending without every write in the app following it. Both targets are the
 * JVM and both actuals are the same `synchronized`, exactly as with the clock
 * and image-scaling actuals; there is no shared JVM source set to hold one copy.
 */
expect class Lock() {
    fun <T> withLock(block: () -> T): T
}
