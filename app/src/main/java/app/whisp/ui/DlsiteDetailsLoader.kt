// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import app.whisp.data.DlsiteClient
import app.whisp.data.DlsiteDetails
import app.whisp.data.DlsiteStatistics
import app.whisp.data.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** DLsite metadata loading with bounded in-memory state, owned by the view model. */
internal class DlsiteDetailsLoader(
    private val scope: CoroutineScope,
    private val repository: LibraryRepository,
) {
    val states = MutableStateFlow<Map<String, DlsiteUiState>>(emptyMap())
    private val jobs = mutableMapOf<String, Job>()

    fun accept(id: String, details: DlsiteDetails) {
        states.update { previous ->
            val state = previous[id] ?: return@update previous
            previous + (id to state.copy(details = details.preserving(state.details)))
        }
    }

    fun load(
        workId: String,
        rj: String,
        part: String = "product",
        seed: DlsiteDetails? = null,
        force: Boolean = false,
        client: DlsiteClient = repository.dlsite,
    ) {
        require(part in setOf("product", "description", "statistics"))
        val key = "$workId|$part"
        if (jobs[key]?.isActive == true) return
        fun update(block: (DlsiteUiState) -> DlsiteUiState) {
            states.update { states ->
                val next = LinkedHashMap(states)
                next[workId] = block(states[workId] ?: DlsiteUiState())
                while (next.size > 12) next.remove(next.keys.first())
                next
            }
        }
        if (jobs.size >= 6) jobs.remove(jobs.keys.first())?.cancel()
        // Register ownership before launching: a cancelled, evicted request can finish
        // after a replacement for the same key has already begun.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val cached =
                        states.value[workId]?.details
                            ?: seed
                            ?: repository.readDlsiteDetails(workId)
                    if (cached != null) update { it.copy(details = cached.preserving(it.details)) }
                    if (isFresh(cached, part, System.currentTimeMillis()) && !force) return@launch
                    update { it.copy(loading = it.loading + part, errors = it.errors - part) }
                    val fresh =
                        when (part) {
                            "product" -> client.work(rj).details
                            "description" -> {
                                val blocks = client.description(rj)
                                (states.value[workId]?.details ?: DlsiteDetails(rj)).copy(
                                    description = blocks,
                                    descriptionFetchedAt = System.currentTimeMillis(),
                                )
                            }
                            else -> {
                                val statistics = client.statistics(rj)
                                (states.value[workId]?.details ?: DlsiteDetails(rj)).copy(
                                    statistics = statistics,
                                )
                            }
                        }
                    val merged = fresh.preserving(states.value[workId]?.details)
                    repository.saveDlsiteDetails(workId, merged)
                    update { it.copy(details = merged.preserving(it.details)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    update { it.copy(errors = it.errors + (part to (e.message ?: "DLsite 读取失败"))) }
                } finally {
                    if (jobs[key] == null || jobs[key] === currentCoroutineContext()[Job]) {
                        update { it.copy(loading = it.loading - part) }
                        jobs.remove(key)
                    }
                }
            }
        jobs[key] = job
        job.invokeOnCompletion { if (jobs[key] === job) jobs.remove(key) }
        job.start()
    }

    companion object {
        fun isFresh(cached: DlsiteDetails?, part: String, nowMs: Long): Boolean =
            when (part) {
                "product" ->
                    cached?.completeProduct == true &&
                        nowMs - cached.productFetchedAt < 7 * 24 * 60 * 60 * 1000L
                "description" -> cached != null && cached.descriptionFetchedAt > 0
                else ->
                    cached?.statistics?.let {
                        nowMs - it.fetchedAt < 24 * 60 * 60 * 1000L
                    } == true
            }
    }
}
