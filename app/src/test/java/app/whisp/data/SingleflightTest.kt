// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class SingleflightTest {
    @Test
    fun concurrentRequestsForTheSameKeyShareOneExecution() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flights = Singleflight<String, String>(scope, 4)
            val executions = AtomicInteger(0)
            val results =
                (1..16).map {
                    async {
                        flights.run("cover:id") {
                            executions.incrementAndGet()
                            delay(50)
                            "bytes"
                        }
                    }
                }
                    .awaitAll()
            assertEquals(1, executions.get())
            assertTrue(results.all { it == "bytes" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun differentKeysRunIndependently() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flights = Singleflight<String, String>(scope, 4)
            val first = async { flights.run("a") { "one" } }
            val second = async { flights.run("b") { "two" } }
            assertEquals("one", first.await())
            assertEquals("two", second.await())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedExecutionDoesNotPoisonLaterRequests() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flights = Singleflight<String, String>(scope, 4)
            val attempts = AtomicInteger(0)
            try {
                flights.run("cover:id") {
                    attempts.incrementAndGet()
                    throw IOException("transient")
                }
                fail("Expected failure")
            } catch (_: IOException) {}
            assertEquals("recovered", flights.run("cover:id") { "recovered" })
            assertEquals(1, attempts.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun completedKeyCanBeFetchedAgain() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flights = Singleflight<String, Int>(scope, 4)
            val calls = AtomicInteger(0)
            assertEquals(1, flights.run("k") { calls.incrementAndGet() })
            assertEquals(2, flights.run("k") { calls.incrementAndGet() })
            assertEquals(2, calls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelledKeyCanBeFetchedAgain() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flights = Singleflight<String, String>(scope, 4)
            val started = Channel<Unit>(1)
            val release = Channel<Unit>(1)
            val first =
                async {
                    try {
                        flights.run("k") {
                            started.send(Unit)
                            release.receive()
                            "stale"
                        }
                    } catch (e: CancellationException) {
                        throw e
                    }
                }
            started.receive()
            flights.cancelAndJoin("k")
            try {
                first.await()
                fail("Expected cancellation")
            } catch (_: CancellationException) {}
            assertEquals("fresh", flights.run("k") { "fresh" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun joinAllWaitsForInflightWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flights = Singleflight<String, String>(scope, 4)
            val release = Channel<Unit>(1)
            val first = async { flights.run("k") { release.receive(); "done" } }
            val joined = async { flights.joinAll() }
            release.send(Unit)
            joined.await()
            assertEquals("done", first.await())
        } finally {
            scope.cancel()
        }
    }
}
