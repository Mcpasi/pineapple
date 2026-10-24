package com.torrenttunes.app.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NetworkAccessTest {
    @Test
    fun `offline search and retry never open a request`() = runBlocking {
        var requests = 0
        repeat(3) {
            try {
                onlineRequest({ false }) { requests++; "unexpected response" }
                fail("Offline request should fail")
            } catch (e: OfflineException) {
                assertEquals(OFFLINE_MESSAGE, e.message)
            }
        }
        assertEquals(0, requests)
    }

    @Test
    fun `online request returns its result once`() = runBlocking {
        var requests = 0
        assertEquals("synthetic result", onlineRequest({ true }) { requests++; "synthetic result" })
        assertEquals(1, requests)
    }

    @Test
    fun `API and rate errors are not relabelled offline`() = runBlocking {
        for (code in listOf(403, 429, 500)) {
            var online = true
            val failure = HttpException(code, "https://fixture.invalid/search", "synthetic API failure")
            try {
                onlineRequest({ online }) { online = false; throw failure }
                fail("API failure should propagate")
            } catch (e: HttpException) {
                assertSame(failure, e)
                assertEquals(code, e.code)
            }
        }
    }

    @Test
    fun `transport failure is offline only when connectivity was lost`() = runBlocking {
        for (lost in listOf(false, true)) {
            var online = true
            val failure = IOException("synthetic transport failure")
            try {
                onlineRequest({ online }) { online = !lost; throw failure }
                fail("Transport failure should propagate")
            } catch (e: IOException) {
                if (lost) assertTrue(e is OfflineException) else assertSame(failure, e)
            }
        }
    }

    @Test
    fun `reconnect keeps results and only explicit retry makes a request`() = runBlocking {
        val subscribed = CompletableDeferred<Unit>()
        lateinit var emit: (Boolean) -> Unit
        var stopped = 0
        var requests = 0
        var notice = ConnectionNotice(true)
        val results = onlineRequest({ true }) { requests++; listOf("synthetic result") }
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            networkChanges({ true }) { callback ->
                emit = callback
                subscribed.complete(Unit)
                val unsubscribe: () -> Unit = { stopped++ }
                unsubscribe
            }.take(3).collect { notice = notice.changed(it) }
        }
        withTimeout(1_000) { subscribed.await() }
        emit(false)
        emit(false)
        emit(true)
        emit(true)
        withTimeout(1_000) { collector.join() }
        assertEquals(listOf("synthetic result"), results)
        assertEquals("Connection restored. You can retry when you're ready.", notice.message(results.isNotEmpty()))
        assertEquals(1, requests)
        assertEquals(1, stopped)
        onlineRequest({ true }) { requests++ }
        assertEquals(2, requests)
    }

    @Test
    fun `cancelled observer unregisters exactly once`() = runBlocking {
        val subscribed = CompletableDeferred<Unit>()
        var registrations = 0
        var removals = 0
        val collector = launch {
            networkChanges({ false }) {
                registrations++
                subscribed.complete(Unit)
                val unsubscribe: () -> Unit = { removals++ }
                unsubscribe
            }.collect()
        }
        withTimeout(1_000) { subscribed.await() }
        collector.cancelAndJoin()
        assertEquals(1, registrations)
        assertEquals(1, removals)
    }

    @Test
    fun `notices require results and a real offline to online transition`() {
        val initial = ConnectionNotice(true)
        assertNull(initial.message(true))
        assertNull(initial.changed(true).message(true))
        val offline = initial.changed(false)
        assertEquals("You're offline. Showing previous results.", offline.message(true))
        assertNull(offline.message(false))
        val reconnected = offline.changed(true)
        assertNull(reconnected.message(false))
        assertEquals(reconnected, reconnected.changed(true))
        assertNull(reconnected.copy(restored = false).message(true))
    }
    @Test
    fun `loss during observer installation needs no later callback`() = runBlocking {
        var online = true
        var removals = 0
        val first = withTimeout(1_000) {
            networkChanges({ online }) {
                online = false
                val unsubscribe: () -> Unit = { removals++ }
                unsubscribe
            }.first()
        }
        assertFalse(first)
        assertEquals(1, removals)
    }

    @Test
    fun `callback during initial snapshot cannot be overwritten by that snapshot`() = runBlocking {
        var observer: ((Boolean) -> Unit)? = null
        val sampled = CompletableDeferred<Unit>()
        val values = mutableListOf<Boolean>()
        var removals = 0
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            networkChanges(
                current = {
                    // The snapshot was online, but loss is delivered before
                    // its read returns. There will be no further callback.
                    observer?.invoke(false)
                    sampled.complete(Unit)
                    true
                },
                subscribe = { callback ->
                    observer = callback
                    val unsubscribe: () -> Unit = { removals++ }
                    unsubscribe
                },
            ).collect { values += it }
        }
        withTimeout(1_000) { sampled.await() }
        // Every producer/collector inherits this runBlocking event loop.
        // Drain queued sends, including a stale initialization if present.
        yield()
        collector.cancelAndJoin()
        assertEquals(listOf(false), values)
        assertEquals(1, removals)
    }

    @Test
    fun `failed initial snapshot does not leak an installed observer`() = runBlocking {
        var registrations = 0
        var removals = 0
        val failure = IllegalStateException("synthetic snapshot failure")
        try {
            networkChanges(
                current = { throw failure },
                subscribe = {
                    registrations++
                    val unsubscribe: () -> Unit = { removals++ }
                    unsubscribe
                },
            ).first()
            fail("Snapshot failure should propagate")
        } catch (e: IllegalStateException) {
            assertEquals(failure.message, e.message)
        }
        assertEquals(registrations, removals)
    }

}
