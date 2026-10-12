// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.ComponentName
import android.os.Bundle
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import app.whisp.data.*
import app.whisp.playback.PlaybackService
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PlaybackState(
    val trackId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val buffering: Boolean = false,
    val timerEnd: Long = 0,
    val loopStart: Long = -1,
    val loopEnd: Long = -1,
    val playWhenReady: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val ended: Boolean = false,
    val failed: Boolean = false,
)

@androidx.annotation.OptIn(UnstableApi::class)
class AppViewModel(val repository: LibraryRepository) : ViewModel() {
    val library = repository.library
    val sources = repository.sources
    val progress = repository.progress
    val bookmarks = repository.bookmarks
    val settings = repository.settings
    val downloads = repository.downloads
    val busy = repository.busy
    val asmrQuery = MutableStateFlow<AsmrQuery?>(null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val asmrCatalog =
        asmrQuery
            .filterNotNull()
            .distinctUntilChanged()
            .flatMapLatest { query ->
                Pager(
                        PagingConfig(
                            pageSize = 20,
                            initialLoadSize = 20,
                            prefetchDistance = 4,
                            maxSize = 100,
                            enablePlaceholders = false,
                        ),
                    ) {
                        AsmrPagingSource { page -> repository.asmrPage(query, page) }
                    }
                    .flow
            }
            .cachedIn(viewModelScope)
    // Event snapshots never advance merely because playback time passes.
    val player = MutableStateFlow(PlaybackState())
    val position = MutableStateFlow(0L)
    val cues = MutableStateFlow<List<Cue>>(emptyList())
    val subtitleLoading = MutableStateFlow(false)
    val openingTrackId = MutableStateFlow<String?>(null)
    val loopDraftStart = MutableStateFlow(-1L)
    private var loopDraftTrack: String? = null
    private var subtitleTrackId: String? = null
    private val subtitleCache = LruCache<String, List<Cue>>(16)
    val error = MutableStateFlow<String?>(null)
    private var subtitleJob: Job? = null
    private var openingJob: Job? = null
    private var openingGeneration = 0
    private var openingPlayWhenReady = true
    private var controller: MediaController? = null
    private var closed = false
    private val future =
        MediaController.Builder(
                repository.context,
                SessionToken(
                    repository.context,
                    ComponentName(repository.context, PlaybackService::class.java),
                ),
            )
            .setListener(
                object : MediaController.Listener {
                    override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                        updatePlayer()
                    }
                },
            )
            .buildAsync()

    init {
        future.addListener(
            {
                if (!closed)
                    runCatching {
                        controller =
                            future.get().also { c ->
                                c.addListener(
                                    object : Player.Listener {
                                        override fun onEvents(
                                            player: Player,
                                            events: Player.Events,
                                        ) {
                                            updatePlayer()
                                        }
                                    },
                                )
                            }
                        updatePlayer()
                    }
                        .onFailure { error.value = "播放服务连接失败" }
            },
            ContextCompat.getMainExecutor(repository.context),
        )
        viewModelScope.launch { repository.messages.collect { error.value = it } }
    }

    internal fun refreshPlayer() = updatePlayer()

    // Called only inside the resumed player page's lifecycle. Events still update
    // pause/seek/buffering immediately, without keeping an idle UI clock running.
    internal suspend fun observeTimeline() {
        updatePlayer()
        player
            .map { it.playing }
            .distinctUntilChanged()
            .collectLatest { playing ->
                if (playing)
                    while (currentCoroutineContext().isActive) {
                        if (openingTrackId.value == null) position.value = currentPosition()
                        delay(200)
                    }
            }
    }

    fun currentPosition(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: position.value

    private fun updatePlayer() {
        val c = controller ?: return
        if (openingTrackId.value != null) return
        val extras = c.sessionExtras
        val id = c.currentMediaItem?.mediaId
        position.value = c.currentPosition.coerceAtLeast(0)
        player.value =
            PlaybackState(
                id,
                c.isPlaying,
                c.currentPosition.coerceAtLeast(0),
                c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
                c.playbackState == Player.STATE_BUFFERING,
                extras.getLong("timerEnd", 0),
                extras.getLong("loopStart", -1),
                extras.getLong("loopEnd", -1),
                c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playerError == null,
                c.hasPreviousMediaItem(),
                c.hasNextMediaItem(),
                c.playbackState == Player.STATE_ENDED,
                c.playerError != null,
            )
        if (loopDraftTrack != id) loopDraftStart.value = -1
        if (id != null && id != subtitleTrackId) loadSubtitles(id)
    }

    private fun resume(c: MediaController) {
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "操作失败"
            }
        }
    }

    fun playWork(
        detail: WorkDetails,
        trackId: String? = null,
        shuffle: Boolean = false,
        startPosition: Long? = null,
        restart: Boolean = false,
    ) {
        val generation = ++openingGeneration
        openingJob?.cancel()
        openingPlayWhenReady = true
        openingJob = viewModelScope.launch {
            try {
                repository.flushPlaybackProgress()
                val checkpoint = repository.dao.progress(detail.work.id)
                val activeId =
                    controller?.currentMediaItem?.mediaId?.takeIf { id ->
                        detail.tracks.any { it.id == id }
                    }
                val tracks =
                    TrackSections.queue(
                        detail.tracks,
                        if (restart) trackId else trackId ?: activeId ?: checkpoint?.trackId,
                    )
                require(tracks.isNotEmpty()) { "作品没有音轨" }
                val recent = progress.value.firstOrNull { it.workId == detail.work.id }
                val provisional =
                    tracks.firstOrNull { it.id == trackId }
                        ?: tracks.firstOrNull { it.id == controller?.currentMediaItem?.mediaId }
                        ?: tracks.firstOrNull { it.id == recent?.trackId }
                        ?: tracks.first()
                openingTrackId.value = provisional.id
                player.value =
                    player.value.copy(
                        trackId = provisional.id,
                        playing = false,
                        positionMs =
                            startPosition
                                ?: if (provisional.id == controller?.currentMediaItem?.mediaId)
                                    currentPosition()
                                else 0,
                        durationMs = provisional.durationMs,
                        buffering = true,
                        playWhenReady = openingPlayWhenReady,
                    )
                position.value = player.value.positionMs
                if (subtitleTrackId != provisional.id) loadSubtitles(provisional.id)
                val c =
                    controller
                        ?: withContext(Dispatchers.IO) { future.get() }.also { controller = it }
                // Continue the active work from the live player, never its older database
                // checkpoint.
                val currentIndex = tracks.indexOfFirst { it.id == c.currentMediaItem?.mediaId }
                if (
                    !restart &&
                        startPosition == null &&
                        !shuffle &&
                        currentIndex >= 0 &&
                        (trackId == null || trackId == tracks[currentIndex].id)
                ) {
                    openingTrackId.value = null
                    if (openingPlayWhenReady) resume(c) else c.pause()
                    updatePlayer()
                    return@launch
                }
                c.pause()
                val point =
                    if (restart) QueuePosition(0, 0)
                    else if (trackId != null)
                        QueuePosition(tracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0), 0)
                    else PlaybackQueue.resume(tracks, checkpoint)
                val position = startPosition?.coerceAtLeast(0) ?: point.positionMs
                val selected = tracks[point.index]
                openingTrackId.value = selected.id
                player.value =
                    player.value.copy(
                        trackId = selected.id,
                        positionMs = position,
                        durationMs = selected.durationMs,
                        playing = false,
                        buffering = true,
                        playWhenReady = openingPlayWhenReady,
                    )
                this@AppViewModel.position.value = position
                loadSubtitles(selected.id)
                val sameQueue =
                    c.mediaItemCount == tracks.size &&
                        tracks.indices.all { c.getMediaItemAt(it).mediaId == tracks[it].id }
                if (sameQueue) c.seekTo(point.index, position)
                else
                    c.setMediaItems(
                        tracks.map { repository.mediaItem(it, detail.work) },
                        point.index,
                        position,
                    )
                c.shuffleModeEnabled = shuffle
                c.repeatMode = Player.REPEAT_MODE_OFF
                c.prepare()
                if (openingPlayWhenReady) c.play() else c.pause()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "无法打开作品"
            } finally {
                if (generation == openingGeneration) {
                    openingTrackId.value = null
                    updatePlayer()
                }
            }
        }
    }

    fun playBookmark(detail: WorkDetails, saved: BookmarkEntity, repeat: Boolean = false) {
        playWork(detail, saved.trackId, startPosition = saved.positionMs)
        val job = openingJob
        val generation = openingGeneration
        viewModelScope.launch {
            job?.join()
            if (repeat && generation == openingGeneration && player.value.trackId == saved.trackId)
                saved.endMs
                    ?.takeIf { it - saved.positionMs >= 500 }
                    ?.let { loop(saved.positionMs, it) }
        }
    }

    fun togglePlay() {
        if (openingJob?.isActive == true) {
            openingPlayWhenReady = !openingPlayWhenReady
            if (!openingPlayWhenReady) controller?.pause()
            player.value = player.value.copy(playWhenReady = openingPlayWhenReady, playing = false)
            return
        }
        controller?.let { c ->
            if (c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playerError == null)
                c.pause()
            else if (c.mediaItemCount > 0) resume(c)
            else
                action {
                    repository.dao.latestProgress()?.let { p ->
                        repository.dao.details(p.workId)?.let { playWork(it) }
                    }
                }
            updatePlayer()
        }
    }

    fun seek(position: Long) {
        controller?.let { c ->
            val duration = c.duration.takeIf { it > 0 && it != C.TIME_UNSET }
            val target =
                if (duration != null) position.coerceIn(0, duration) else position.coerceAtLeast(0)
            val state = player.value
            if (state.loopStart >= 0 && target !in state.loopStart..state.loopEnd) loop()
            c.seekTo(target)
            updatePlayer()
        }
    }

    fun skip(delta: Long) {
        seek((controller?.currentPosition ?: player.value.positionMs) + delta)
    }

    fun next() {
        controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
        updatePlayer()
    }

    fun previous() {
        controller?.takeIf { it.hasPreviousMediaItem() }?.seekToPreviousMediaItem()
        updatePlayer()
    }

    fun timer(minutes: Int) {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.TIMER, Bundle.EMPTY),
            Bundle().apply { putInt("minutes", minutes) },
        )
    }

    fun loop(start: Long = -1, end: Long = -1) {
        player.value = player.value.copy(loopStart = start, loopEnd = end)
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.LOOP, Bundle.EMPTY),
            Bundle().apply {
                putLong("start", start)
                putLong("end", end)
            },
        )
    }

    fun favorite(id: String) = action { repository.dao.toggleFavorite(id) }

    fun download(id: String) = action { repository.downloadWork(id) }

    fun markLoopStart() {
        loopDraftTrack = player.value.trackId
        loopDraftStart.value = currentPosition()
        loop()
    }

    fun markLoopEnd(): Boolean {
        val start = loopDraftStart.value
        val end = currentPosition()
        if (start < 0 || end - start < 500) {
            error.value = "循环终点需晚于起点"
            return false
        }
        loop(start, end)
        loopDraftStart.value = -1
        return true
    }

    fun clearLoop() {
        loop()
        loopDraftStart.value = -1
    }

    fun saveBookmark(
        title: String,
        endMs: Long? = null,
        positionMs: Long = currentPosition(),
        trackId: String? = player.value.trackId,
    ) = action {
        val track = repository.dao.track(trackId ?: return@action) ?: return@action
        repository.dao.saveBookmark(
            BookmarkEntity(
                UUID.randomUUID().toString(),
                track.workId,
                track.id,
                positionMs.coerceAtLeast(0),
                title.ifBlank { track.title },
                endMs,
            ),
        )
    }

    fun loadSubtitles(id: String?) {
        subtitleJob?.cancel()
        subtitleTrackId = id
        cues.value = id?.let { subtitleCache[it] }.orEmpty()
        subtitleLoading.value = false
        if (id == null || subtitleCache[id] != null) return
        subtitleJob = viewModelScope.launch {
            try {
                val track = repository.dao.track(id) ?: return@launch
                val attachmentId = track.subtitleId ?: return@launch
                subtitleLoading.value = true
                val detail = repository.dao.details(track.workId) ?: return@launch
                val attachment =
                    detail.attachments.firstOrNull { it.id == attachmentId } ?: return@launch
                val bytes = repository.readAsset(attachment.id)
                val parsed =
                    withContext(Dispatchers.Default) {
                        SubtitleTimeline.parse(
                            bytes.toString(Charsets.UTF_8),
                            attachment.fileName.substringAfterLast('.'),
                        )
                    }
                ensureActive()
                subtitleCache.put(id, parsed)
                if (subtitleTrackId == id) cues.value = parsed
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (subtitleTrackId == id) error.value = "字幕暂时不可用"
            } finally {
                if (subtitleTrackId == id) subtitleLoading.value = false
            }
        }
    }

    override fun onCleared() {
        closed = true
        MediaController.releaseFuture(future)
    }
}
