package com.torrenttunes.app.search

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.IOException

internal const val OFFLINE_MESSAGE = "You're offline. Check your connection and try again."

internal class OfflineException : IOException(OFFLINE_MESSAGE)

/** Check at the request boundary, not just when the screen last composed. */
internal suspend fun <T> onlineRequest(isOnline: () -> Boolean, request: suspend () -> T): T {
    if (!isOnline()) throw OfflineException()
    return try {
        request()
    } catch (e: IOException) {
        // An HTTP/API failure must retain its own message even if the network
        // changes. Only a transport failure can become an offline failure.
        if (!isOnline()) throw OfflineException()
        throw e
    }
}

internal fun networkChanges(
    current: () -> Boolean,
    subscribe: ((Boolean) -> Unit) -> (() -> Unit),
): Flow<Boolean> = callbackFlow {
    val lock = Any()
    var observed = false
    // Install first: a network lost before registration may never deliver a
    // callback. A callback received during the fallback snapshot is newer
    // than that snapshot, and must not be overwritten by initialization.
    val unsubscribe = subscribe { online ->
        synchronized(lock) {
            observed = true
            trySend(online)
        }
    }
    try {
        val initial = current()
        synchronized(lock) {
            if (!observed) trySend(initial)
        }
        awaitClose()
    } finally {
        unsubscribe()
    }
}.distinctUntilChanged()

internal data class ConnectionNotice(val online: Boolean, val restored: Boolean = false) {
    fun changed(online: Boolean): ConnectionNotice =
        if (online == this.online) this else ConnectionNotice(online, restored = online)

    fun message(hasResults: Boolean): String? = when {
        !hasResults -> null
        !online -> "You're offline. Showing previous results."
        restored -> "Connection restored. You can retry when you're ready."
        else -> null
    }
}
