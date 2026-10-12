// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Retain the latest pending position per work, including when several works change. */
internal class ProgressWriter(
    scope: CoroutineScope,
    private val write: suspend (ProgressEntity) -> Unit,
    private val failed: (Exception) -> Unit = {},
) {
    private val pending = LinkedHashMap<String, ProgressEntity>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val writes = Mutex()
    private var latest: ProgressEntity? = null

    init {
        scope.launch {
            for (ignored in signal) {
                // Merge the callbacks produced by a single pause/seek/transition.
                delay(150)
                try {
                    flush()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed(e)
                    delay(5000)
                    signal.trySend(Unit)
                }
            }
        }
    }

    fun save(progress: ProgressEntity) =
        synchronized(pending) {
            if (
                latest?.let {
                    it.workId == progress.workId &&
                        it.trackId == progress.trackId &&
                        it.positionMs == progress.positionMs
                } == true
            )
                return@synchronized
            latest = progress
            pending.remove(progress.workId)
            pending[progress.workId] = progress
            signal.trySend(Unit)
            Unit
        }

    suspend fun flush() = writes.withLock {
        while (true) {
            val progress =
                synchronized(pending) {
                    pending.entries.firstOrNull()?.value?.also { pending.remove(it.workId) }
                } ?: break
            try {
                write(progress)
            } catch (e: Exception) {
                synchronized(pending) { pending.putIfAbsent(progress.workId, progress) }
                throw e
            }
        }
    }
}
