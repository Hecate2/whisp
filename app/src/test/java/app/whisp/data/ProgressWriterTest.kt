// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProgressWriterTest {
    @Test fun burstsKeepTheLatestPositionForEveryWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val saved = mutableListOf<ProgressEntity>()
            val writer = ProgressWriter(scope, { saved += it })
            writer.save(ProgressEntity("a", "a1", 100, 1))
            writer.save(ProgressEntity("b", "b1", 200, 2))
            writer.save(ProgressEntity("a", "a2", 300, 3))
            writer.flush()
            assertEquals(listOf("b", "a"), saved.map { it.workId })
            assertEquals(listOf(200L, 300L), saved.map { it.positionMs })
            assertEquals("a2", saved.last().trackId)
        } finally { scope.cancel() }
    }
    @Test fun updatesDuringAnInFlightWriteAreNotLostOrReordered() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = Channel<Unit>(1); val release = Channel<Unit>(1)
        val saved = mutableListOf<ProgressEntity>()
        try {
            val writer = ProgressWriter(scope, {
                if (saved.isEmpty()) { entered.send(Unit); release.receive() }
                saved += it
            })
            writer.save(ProgressEntity("a", "a1", 100, 1))
            val flush = async { writer.flush() }
            entered.receive()
            writer.save(ProgressEntity("a", "a2", 900, 2))
            writer.save(ProgressEntity("b", "b1", 200, 3))
            release.send(Unit); flush.await()
            assertEquals(listOf(100L, 900L, 200L), saved.map { it.positionMs })
        } finally { scope.cancel() }
    }
    @Test fun aFailedWriteNeverOverwritesANewerPendingPosition() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var failWrites = true
            val saved = mutableListOf<ProgressEntity>()
            val writer = ProgressWriter(scope, { if (failWrites) throw IOException("disk"); saved += it })
            writer.save(ProgressEntity("a", "a1", 100))
            try { writer.flush(); fail("Expected write failure") } catch (_: IOException) { }
            writer.save(ProgressEntity("a", "a2", 900))
            writer.save(ProgressEntity("b", "b1", 200))
            failWrites = false; writer.flush()
            assertEquals(listOf(900L, 200L), saved.map { it.positionMs })
        } finally { scope.cancel() }
    }
    @Test fun duplicateCallbacksAreIgnoredButReturningToAnotherWorkIsSaved() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val saved = mutableListOf<ProgressEntity>()
            val writer = ProgressWriter(scope, { saved += it })
            writer.save(ProgressEntity("a", "a1", 100, 1)); writer.flush()
            writer.save(ProgressEntity("a", "a1", 100, 2)); writer.flush()
            assertEquals(1, saved.size)
            writer.save(ProgressEntity("b", "b1", 100, 3)); writer.flush()
            writer.save(ProgressEntity("a", "a1", 100, 4)); writer.flush()
            assertEquals(listOf("a", "b", "a"), saved.map { it.workId })
        } finally { scope.cancel() }
    }
}
