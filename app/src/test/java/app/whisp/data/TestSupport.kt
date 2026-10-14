// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer

fun runIsolatedTest(block: suspend (scope: CoroutineScope) -> Unit) = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        block(scope)
    } finally {
        scope.cancel()
    }
}

/** Two-channel gate for parking a worker until the test lets it proceed. */
class Gate {
    private val arrival = Channel<Unit>(1)
    private val release = Channel<Unit>(1)

    suspend fun arrive() {
        arrival.send(Unit)
        release.receive()
    }

    suspend fun awaitArrival() {
        arrival.receive()
    }

    suspend fun open() {
        release.send(Unit)
    }
}

suspend fun <T> withMockServer(block: suspend (MockWebServer) -> T): T {
    MockWebServer().use { server ->
        server.start()
        return block(server)
    }
}
