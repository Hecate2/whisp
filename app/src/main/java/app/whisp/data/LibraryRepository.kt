// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.*
import app.whisp.playback.OfflineService
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

private val Context.preferences by preferencesDataStore("settings")

data class Settings(
    val subtitleSize: Int = 21,
    val subtitleOffsetMs: Long = 0,
    val hideArtwork: Boolean = false,
    val reduceMotion: Boolean = false,
    val streamingCacheMiB: Int = 128,
    val hideRecents: Boolean = false,
    val touchFeedback: Boolean = true,
)

@androidx.annotation.OptIn(UnstableApi::class)
class LibraryRepository(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db").build()
    val dao = database.library()
    val secrets = SecretStore()
    // Redirects are disabled so credentials never travel to a different server.
    val http =
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    val dav = WebDavClient(http)
    val smb = SmbClient { Log.i("WhispScan", it) }
    val library = dao.observeLibrary().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val sources = dao.observeSources().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val progress = dao.observeProgress().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val storageChanges = MutableStateFlow(0L)

    private fun storageChanged() {
        storageChanges.update { it + 1 }
    }

    private val progressWriter =
        ProgressWriter(
            scope,
            {
                dao.saveProgress(it)
                storageChanged()
            },
            { Log.w("WhispProgress", "Progress write deferred: ${it.javaClass.simpleName}") },
        )

    fun savePlaybackProgress(progress: ProgressEntity) {
        progressWriter.save(progress)
    }

    suspend fun flushPlaybackProgress() {
        progressWriter.flush()
    }

    val bookmarks = dao.observeBookmarks().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val busy = MutableStateFlow<Set<String>>(emptySet())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val artworkRevision = MutableStateFlow(0)
    private val sizeKey = intPreferencesKey("subtitle-size")
    private val offsetKey = longPreferencesKey("subtitle-offset")
    private val privateKey = booleanPreferencesKey("hide-artwork")
    private val motionKey = booleanPreferencesKey("reduce-motion")
    private val cacheKey = intPreferencesKey("streaming-cache-mib")
    private val recentsKey = booleanPreferencesKey("hide-recents")
    private val feedbackKey = booleanPreferencesKey("touch-feedback")
    // This flow has no placeholder value: background resumption must read real privacy preferences.
    val settingsUpdates =
        context.preferences.data.map {
            Settings(
                it[sizeKey] ?: 21,
                it[offsetKey] ?: 0,
                it[privateKey] ?: false,
                it[motionKey] ?: false,
                it[cacheKey] ?: 128,
                it[recentsKey] ?: false,
                it[feedbackKey] ?: true,
            )
        }
    val settings = settingsUpdates.stateIn(scope, SharingStarted.Eagerly, Settings())
    private val offlineDir = File(context.filesDir, "offline/attachments").apply { mkdirs() }
    private val artworkDir = File(context.filesDir, "artwork").apply { mkdirs() }
    private val customArtworkDir = File(context.filesDir, "custom-artwork").apply { mkdirs() }
    private val artworkCache =
        ImageFileCache(File(context.cacheDir, "artwork"), 64 * MIB, ::storageChanged)
    private val previewCache =
        ImageFileCache(File(context.cacheDir, "previews"), 24 * MIB, ::storageChanged)
    private val imageWrites = Mutex()
    private val imageLanes = Semaphore(4)
    private val imageRequests = mutableMapOf<String, Deferred<File?>>()
    private val storageReady = CompletableDeferred<Unit>()
    val artworkReload = MutableStateFlow(0)
    private val streamingEvictor = AdjustableCacheEvictor(Long.MAX_VALUE, ::storageChanged)
    private val mediaDatabase = StandaloneDatabaseProvider(context)
    val downloadCache =
        SimpleCache(File(context.filesDir, "offline/audio"), NoOpCacheEvictor(), mediaDatabase)
    private val streamingCache =
        SimpleCache(
            File(context.cacheDir, "streaming"),
            streamingEvictor,
            mediaDatabase,
        )
    private val httpFactory =
        ResolvingDataSource.Factory(
            DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http)),
        ) { spec ->
            spec.withRequestHeaders(spec.httpRequestHeaders + headers(spec.uri.toString()))
        }
    private val networkFactory = DataSource.Factory {
        val fallback = httpFactory.createDataSource()
        val smbData = SmbDataSource { uri ->
            val sourceId = Uri.parse(uri).authority ?: throw IOException("来源未关联")
            val source =
                runBlocking(Dispatchers.IO) { dao.source(sourceId) } ?: throw IOException("来源已移除")
            require(source.kind == "smb") { "来源类型不匹配" }
            smb.open(source, secrets.decrypt(source.secret), uri)
        }
        object : DataSource {
            private var active: DataSource = fallback

            override fun addTransferListener(listener: TransferListener) {
                fallback.addTransferListener(listener)
                smbData.addTransferListener(listener)
            }

            override fun open(spec: DataSpec): Long {
                active = if (spec.uri.scheme == "whisp-smb") smbData else fallback
                return active.open(spec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int) =
                active.read(buffer, offset, length)

            override fun getUri() = active.uri

            override fun getResponseHeaders() = active.responseHeaders

            override fun close() = active.close()
        }
    }
    private val streamingFactory =
        CacheDataSource.Factory()
            .setCache(streamingCache)
            .setUpstreamDataSourceFactory(networkFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    val playbackFactory =
        CacheDataSource.Factory()
            .setCache(downloadCache)
            // Only DownloadManager writes pinned copies. Playback must not hold a cache
            // hole while paused, which would block a concurrent download of that track.
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(streamingFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    val downloadManager =
        DownloadManager(
                context,
                mediaDatabase,
                downloadCache,
                networkFactory,
                Executors.newFixedThreadPool(2),
            )
            .apply {
                maxParallelDownloads = 2
                // Local copies and isolated NAS networks do not require validated Internet.
                requirements = Requirements(Requirements.DEVICE_STORAGE_NOT_LOW)
            }
    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    private val downloadRefresh = Channel<Unit>(Channel.CONFLATED)
    private val activeDownloads = MutableStateFlow(false)

    init {
        scope.launch {
            try {
                migrateImages()
            } catch (e: Exception) {
                Log.w("WhispStorage", "Image migration deferred: ${e.javaClass.simpleName}")
            } finally {
                storageReady.complete(Unit)
                artworkRevision.update { it + 1 }
            }
        }
        scope.launch {
            context.preferences.data
                .map { it[cacheKey] ?: 128 }
                .distinctUntilChanged()
                .collect {
                    streamingEvictor.resize(streamingCache, it * MIB)
                }
        }
        downloadManager.addListener(
            object : DownloadManager.Listener {
                override fun onInitialized(manager: DownloadManager) {
                    downloadRefresh.trySend(Unit)
                }

                override fun onDownloadChanged(
                    manager: DownloadManager,
                    download: Download,
                    finalException: Exception?,
                ) {
                    downloadRefresh.trySend(Unit)
                    storageChanged()
                    finalException?.let { messages.tryEmit("下载失败，可在来源页重试") }
                }

                override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                    downloadRefresh.trySend(Unit)
                    storageChanged()
                }
            },
        )
        scope.launch(Dispatchers.Main.immediate) {
            for (ignored in downloadRefresh) {
                val stored = withContext(Dispatchers.IO) { readDownloads() }
                downloads.value = stored
                sampleDownloadProgress()
            }
        }
        downloadRefresh.trySend(Unit)
        scope.launch(Dispatchers.Main.immediate) {
            combine(downloads.subscriptionCount, activeDownloads) { observers, active ->
                    observers > 0 && active
                }
                .distinctUntilChanged()
                .collectLatest { visibleActive ->
                    if (visibleActive)
                        while (isActive) {
                            sampleDownloadProgress()
                            delay(1500)
                        }
                }
        }
    }

    private fun readDownloads(): Map<String, Download> {
        val result = mutableMapOf<String, Download>()
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) cursor.download.let { result[it.request.id] = it }
        }
        return result
    }

    // DownloadManager mutates its in-memory byte counters. Copy them for StateFlow
    // delivery; progress sampling never traverses the download database.
    private fun sampleDownloadProgress() {
        val current = downloadManager.currentDownloads
        activeDownloads.value = current.any { it.state == Download.STATE_DOWNLOADING }
        val changed = current.filter { live ->
            val old = downloads.value[live.request.id]
            old == null ||
                old.state != live.state ||
                old.bytesDownloaded != live.bytesDownloaded ||
                old.percentDownloaded != live.percentDownloaded ||
                old.contentLength != live.contentLength
        }
        if (changed.isEmpty()) return
        downloads.update { previous ->
            previous +
                changed.associate { live ->
                    live.request.id to
                        Download(
                            live.request,
                            live.state,
                            live.startTimeMs,
                            live.updateTimeMs,
                            live.contentLength,
                            live.stopReason,
                            live.failureReason,
                            DownloadProgress().apply {
                                bytesDownloaded = live.bytesDownloaded
                                percentDownloaded = live.percentDownloaded
                            },
                        )
                }
        }
    }

    fun headers(uri: String): Map<String, String> {
        val url = uri.toHttpUrlOrNull() ?: return emptyMap()
        val knownSources = sources.value.ifEmpty { runBlocking(Dispatchers.IO) { dao.sources() } }
        val source =
            knownSources.firstOrNull { source ->
                source.kind == "webdav" &&
                    (source.root.trimEnd('/') + "/").toHttpUrlOrNull()?.let {
                        WebDavClient.insideRoot(url, it)
                    } == true
            } ?: return emptyMap()
        if (source.username.isBlank()) return emptyMap()
        return mapOf(
            "Authorization" to
                Credentials.basic(source.username, secrets.decrypt(source.secret), Charsets.UTF_8),
        )
    }

    suspend fun setSettings(
        size: Int? = null,
        offset: Long? = null,
        hide: Boolean? = null,
        reduceMotion: Boolean? = null,
        streamingCacheMiB: Int? = null,
        hideRecents: Boolean? = null,
        touchFeedback: Boolean? = null,
    ) {
        context.preferences.edit { prefs ->
            size?.let { prefs[sizeKey] = it.coerceIn(16, 30) }
            offset?.let { prefs[offsetKey] = it.coerceIn(-10000, 10000) }
            hide?.let { prefs[privateKey] = it }
            reduceMotion?.let { prefs[motionKey] = it }
            hideRecents?.let { prefs[recentsKey] = it }
            touchFeedback?.let { prefs[feedbackKey] = it }
            streamingCacheMiB?.let {
                require(it in listOf(64, 128, 256, 512))
                prefs[cacheKey] = it
            }
        }
    }

    suspend fun addLocal(root: String, name: String): String {
        val id = stableId(root)
        dao.putSource(SourceEntity(id, name, "local", root))
        scheduleScan(id)
        return id
    }

    suspend fun addWebDav(name: String, root: String, username: String, password: String): String {
        val url = root.trim().toHttpUrlOrNull() ?: error("请输入有效的 HTTPS 地址")
        require(url.scheme == "https" || url.host in setOf("localhost", "127.0.0.1", "10.0.2.2")) {
            "请使用 HTTPS 连接 NAS"
        }
        require(
            url.username.isEmpty() &&
                url.password.isEmpty() &&
                url.query == null &&
                url.fragment == null,
        ) {
            "账号请填写在账号栏"
        }
        val normalized =
            url.newBuilder()
                .apply { if (!url.encodedPath.endsWith('/')) addPathSegment("") }
                .build()
                .toString()
        val id = stableId(normalized + "|" + username)
        val source =
            SourceEntity(
                id,
                name.ifBlank { url.host },
                "webdav",
                normalized,
                username.trim(),
                secrets.encrypt(password),
            )
        withContext(Dispatchers.IO) { dav.list(normalized.toHttpUrlOrNull()!!, source, password) }
        dao.putSource(source)
        scheduleScan(id)
        return id
    }

    suspend fun addSmb(name: String, root: String, username: String, password: String): String {
        val address = SmbAddress.parse(root)
        val account = username.trim()
        SmbAccount.parse(account)
        val id = stableId("smb|${address.normalized}|$account")
        val source =
            SourceEntity(
                id,
                name.ifBlank { address.host },
                "smb",
                address.normalized,
                account,
                secrets.encrypt(password),
            )
        withContext(Dispatchers.IO) { smb.check(source, password) }
        dao.putSource(source)
        scheduleScan(id)
        return id
    }

    fun scheduleScan(id: String) {
        if (id in busy.value) return
        val request =
            OneTimeWorkRequestBuilder<ScanWorker>()
                .setInputData(workDataOf("sourceId" to id))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        // A user refresh must replace an interrupted request awaiting its retry.
        WorkManager.getInstance(context)
            .enqueueUniqueWork("scan-$id", ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun scan(id: String) {
        val source = dao.source(id) ?: return
        busy.update { it + id }
        val startTime = android.os.SystemClock.elapsedRealtime()
        try {
            Log.i("WhispScan", "Started ${source.kind} scan")
            val entries =
                when (source.kind) {
                    "local" -> LocalDirectory(context).walk(source.root)
                    "smb" -> smb.walk(source, secrets.decrypt(source.secret))
                    "webdav" -> dav.walk(source, secrets.decrypt(source.secret))
                    else -> error("不支持的来源类型")
                }
            val generation = System.currentTimeMillis()
            val indexed = LibraryIndexer.index(entries, id, generation)
            Log.i(
                "WhispScan",
                "Listed ${entries.size} files; indexed ${indexed.works.size} works, ${indexed.tracks.size} tracks",
            )
            require(indexed.tracks.isNotEmpty()) { "目录中未找到音频，请检查来源目录" }
            currentCoroutineContext().ensureActive()
            var savedTracks: List<TrackEntity> = emptyList()
            database.withTransaction {
                val oldWorks = dao.allWorks().associateBy { it.id }
                val oldTracks = dao.allTracks().associateBy { it.id }
                dao.putWorks(
                    indexed.works.map { new ->
                        oldWorks[new.id]?.let { old ->
                            new.copy(
                                title = old.title,
                                artist = old.artist,
                                favorite = old.favorite,
                                coverId = old.coverId ?: new.coverId,
                                addedAt = old.addedAt,
                                offlineAttachments = old.offlineAttachments,
                            )
                        } ?: new
                    },
                )
                savedTracks =
                    indexed.tracks.map { new ->
                        oldTracks[new.id]?.let { new.copy(durationMs = it.durationMs) } ?: new
                    }
                dao.putTracks(savedTracks)
                dao.putAttachments(indexed.attachments)
                dao.putLocations(indexed.locations)
                dao.removeStaleLocations(id, generation)
                dao.putSource(source.copy(lastScan = generation, error = null))
            }
            Log.i(
                "WhispScan",
                "Library saved in ${android.os.SystemClock.elapsedRealtime() - startTime} ms",
            )
            enrich(source, indexed, savedTracks)
            Log.i(
                "WhispScan",
                "Scan completed in ${android.os.SystemClock.elapsedRealtime() - startTime} ms",
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            dao.putSource(source.copy(error = error.message ?: "来源不可用"))
            messages.tryEmit("${source.name}：${error.message ?: "扫描失败"}")
            Log.w("WhispScan", "Scan failed: ${error.javaClass.simpleName}")
            throw error
        } finally {
            busy.update { it - id }
        }
    }

    private suspend fun enrich(
        source: SourceEntity,
        indexed: IndexedLibrary,
        tracks: List<TrackEntity>,
    ) = coroutineScope {
        data class Asset(val id: String, val cover: Boolean)
        val tasks = mutableListOf<Asset>()
        val byWork = tracks.groupBy { it.workId }
        indexed.works.forEach { work ->
            work.coverId?.takeUnless { artwork(it) != null }?.let { tasks += Asset(it, true) }
            byWork[work.id]
                .orEmpty()
                .filter {
                    it.durationMs == 0L &&
                        (source.kind == "local" || it.fileName.endsWith(".mp3", true))
                }
                .forEach { tasks += Asset(it.id, false) }
        }
        if (tasks.isEmpty()) return@coroutineScope
        val locations = indexed.locations.associateBy { it.assetId }
        val password = if (source.kind != "local") secrets.decrypt(source.secret) else ""
        val lanes = minOf(4, tasks.size)
        val processed = java.util.concurrent.atomic.AtomicInteger()
        val skipped = java.util.concurrent.atomic.AtomicInteger()
        (0 until lanes)
            .map { lane ->
                async(Dispatchers.IO) {
                    var session: SmbClient.AssetSession? = null
                    val durations = mutableListOf<Pair<String, Long>>()
                    suspend fun flush() {
                        if (durations.isEmpty()) return
                        database.withTransaction {
                            durations.forEach { (id, duration) -> dao.updateDuration(id, duration) }
                        }
                        durations.clear()
                    }
                    try {
                        if (source.kind == "smb") session = smb.assets(source, password)
                        tasks
                            .filterIndexed { index, _ -> index % lanes == lane }
                            .forEachIndexed { index, asset ->
                                ensureActive()
                                try {
                                    if (asset.cover) {
                                        if (session != null) {
                                            storageReady.await()
                                            cacheArtwork(
                                                asset.id,
                                                session!!.bytes(
                                                    locations.getValue(asset.id).uri,
                                                    16 * 1024 * 1024,
                                                ),
                                            )
                                            artworkRevision.update { it + 1 }
                                        } else ensureArtwork(asset.id)
                                    } else {
                                        val location = locations.getValue(asset.id)
                                        val duration =
                                            when (source.kind) {
                                                "smb" -> session!!.duration(location.uri)
                                                "local" -> localDuration(asset.id)
                                                else ->
                                                    Mp3Metadata.duration(location.size) {
                                                        offset,
                                                        length ->
                                                        dav.range(
                                                            location.uri,
                                                            source,
                                                            password,
                                                            offset,
                                                            length,
                                                        )
                                                    }
                                            }
                                        if (duration > 0) durations += asset.id to duration
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    skipped.incrementAndGet()
                                }
                                val completed = processed.incrementAndGet()
                                if (completed % 200 == 0)
                                    Log.i(
                                        "WhispScan",
                                        "Metadata $completed/${tasks.size}; skipped ${skipped.get()}",
                                    )
                                if ((index + 1) % 50 == 0) flush()
                            }
                        flush()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        Log.w(
                            "WhispScan",
                            "Metadata lane unavailable; durations can be learned during playback",
                        )
                    } finally {
                        session?.close()
                    }
                }
            }
            .awaitAll()
        Log.i(
            "WhispScan",
            "Metadata completed ${processed.get()}/${tasks.size}; skipped ${skipped.get()}",
        )
    }

    suspend fun removeSource(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("scan-$id")
        database.withTransaction {
            dao.deleteSourceLocations(id)
            dao.deleteSource(id)
        }
    }

    suspend fun resolve(id: String): String {
        val offline = File(offlineDir, id)
        if (offline.isFile) return Uri.fromFile(offline).toString()
        downloadManager.downloadIndex
            .getDownload(id)
            ?.takeIf { it.state == Download.STATE_COMPLETED }
            ?.let {
                return it.request.uri.toString()
            }
        val options = dao.locations(id)
        return options
            .firstOrNull { it.uri.startsWith("content:") || it.uri.startsWith("file:") }
            ?.uri ?: options.firstOrNull()?.uri ?: throw IOException("文件已移动或来源已移除")
    }

    fun artwork(id: String?): File? = id?.let { asset ->
        File(offlineDir, asset).takeIf { it.isFile }
            ?: File(customArtworkDir, asset).takeIf { it.isFile }
            ?: artworkCache.get(asset)
            ?: File(artworkDir, asset).takeIf { it.isFile }
    }

    private suspend fun cacheArtwork(id: String, bytes: ByteArray): File {
        val optimized = withContext(Dispatchers.IO) { ImageOptimizer.optimize(bytes) }
        return imageWrites.withLock { artworkCache.put(id, optimized) }
    }

    suspend fun ensureArtwork(id: String): File? =
        imageRequest("cover:$id") {
            storageReady.await()
            artwork(id)
                ?: if (dao.locations(id).isEmpty()) null
                else {
                    cacheArtwork(id, readAsset(id, 16 * 1024 * 1024)).also {
                        artworkRevision.update { it + 1 }
                    }
                }
        }

    suspend fun previewImage(id: String): File =
        imageRequest("preview:$id") {
            storageReady.await()
            File(offlineDir, id).takeIf { it.isFile }
                ?: previewCache.get(id)
                ?: run {
                    val bytes = ImageOptimizer.optimize(readAsset(id, 64 * 1024 * 1024), 2560)
                    imageWrites.withLock { previewCache.put(id, bytes) }
                }
        } ?: throw IOException("图片不可读")

    private suspend fun imageRequest(key: String, block: suspend () -> File?): File? {
        val request =
            synchronized(imageRequests) {
                imageRequests[key]
                    ?: scope
                        .async(start = CoroutineStart.LAZY) { imageLanes.withPermit { block() } }
                        .also { task ->
                            imageRequests[key] = task
                            // A screen can disappear before the shared fetch completes. Cleanup
                            // belongs
                            // to the fetch itself, otherwise a later request can reuse a deleted
                            // file.
                            task.invokeOnCompletion {
                                synchronized(imageRequests) {
                                    if (imageRequests[key] === task) imageRequests.remove(key)
                                }
                            }
                            task.start()
                        }
            }
        return request.await()
    }

    /** Only indexed remote originals move to cache; custom and offline files remain durable. */
    internal suspend fun migrateImages() =
        withContext(Dispatchers.IO) {
            imageWrites.withLock {
                val works = dao.allWorks()
                for (work in works) {
                    val id = work.coverId ?: continue
                    val old = File(artworkDir, id)
                    if (!old.isFile) continue
                    if (id == stableId("${work.id}|custom-cover")) {
                        val target = File(customArtworkDir, id)
                        if (!target.isFile) atomicWrite(target, old.readBytes())
                        if (target.isFile) old.delete()
                    } else if (dao.locations(id).isNotEmpty()) {
                        if (artworkCache.get(id) == null)
                            artworkCache.put(id, ImageOptimizer.optimize(old.readBytes()))
                        if (artworkCache.get(id) != null) old.delete()
                    }
                }
                // Previous versions put previews directly in cacheDir. Identify them by DB ID.
                dao.imageAttachments().forEach { id ->
                    val old = File(context.cacheDir, id)
                    if (old.isFile) {
                        if (previewCache.get(id) == null)
                            previewCache.put(id, ImageOptimizer.optimize(old.readBytes(), 2560))
                        if (previewCache.get(id) != null) old.delete()
                    }
                }
            }
        }

    suspend fun storageUsage(): StorageUsage =
        withContext(Dispatchers.IO) {
            storageReady.await()
            fun size(file: File): Long =
                if (file.isFile) file.length() else file.listFiles()?.sumOf { size(it) } ?: 0L
            StorageUsage(
                artworkCache.size(),
                previewCache.size(),
                size(File(context.cacheDir, "streaming")),
                size(File(context.filesDir, "offline/audio")) + size(offlineDir),
                size(customArtworkDir),
                size(File(context.dataDir, "databases")) +
                    size(File(context.filesDir, "datastore")) +
                    size(File(context.dataDir, "shared_prefs")) +
                    size(context.noBackupFilesDir) +
                    size(artworkDir),
            )
        }

    suspend fun clearTemporaryCache() =
        withContext(Dispatchers.IO) {
            storageReady.await()
            // Let already requested images finish before clearing so they cannot restore stale
            // cache.
            val requests = synchronized(imageRequests) { imageRequests.values.toList() }
            requests.forEach {
                try {
                    it.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
            imageWrites.withLock {
                artworkCache.clear()
                previewCache.clear()
            }
            synchronized(streamingCache) {
                streamingCache.keys.toList().forEach { streamingCache.removeResource(it) }
            }
            artworkRevision.update { it + 1 }
            artworkReload.update { it + 1 }
        }

    private fun atomicWrite(file: File, bytes: ByteArray) = atomicFile(file, bytes)

    suspend fun readAsset(id: String, limit: Int = 8 * 1024 * 1024): ByteArray =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(resolve(id))
            when (uri.scheme) {
                "content",
                "file" ->
                    context.contentResolver.openInputStream(uri)?.use { boundedRead(it, limit) }
                        ?: throw IOException("文件不可读")
                else -> {
                    val location =
                        dao.locations(id).firstOrNull { it.uri == uri.toString() } ?: error("来源未关联")
                    val source = dao.source(location.sourceId) ?: error("来源已移除")
                    if (source.kind == "smb")
                        smb.bytes(uri.toString(), source, secrets.decrypt(source.secret), limit)
                    else dav.bytes(uri.toString(), source, secrets.decrypt(source.secret), limit)
                }
            }
        }

    private suspend fun localDuration(id: String): Long {
        val uri = Uri.parse(resolve(id))
        if (uri.scheme !in setOf("content", "file")) return 0
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: 0
        } finally {
            retriever.release()
        }
    }

    suspend fun probeDuration(id: String) =
        withContext(Dispatchers.IO) {
            val duration = localDuration(id)
            if (duration > 0) dao.updateDuration(id, duration)
        }

    fun mediaMetadata(
        track: TrackEntity,
        work: WorkEntity,
        hideArtwork: Boolean = settings.value.hideArtwork,
    ): MediaMetadata {
        val metadata =
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(work.artist.ifBlank { work.title })
                .setAlbumTitle(work.title)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                .setExtras(android.os.Bundle().apply { putString("workId", work.id) })
        if (!hideArtwork)
            artwork(work.coverId)?.let { metadata.setArtworkUri(Uri.fromFile(it as File)) }
        if (hideArtwork) metadata.setTitle("正在收听").setArtist("Whisp").setAlbumTitle("Whisp")
        return metadata.build()
    }

    suspend fun mediaItem(track: TrackEntity, work: WorkEntity): MediaItem {
        val hide = settingsUpdates.first().hideArtwork
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(resolve(track.id))
            .setCustomCacheKey(track.id)
            .setMediaMetadata(mediaMetadata(track, work, hide))
            .build()
    }

    suspend fun downloadWork(id: String) {
        val detail = dao.details(id) ?: return
        require(detail.tracks.isNotEmpty()) { "作品没有音轨" }
        for (track in detail.tracks) {
            val uri = resolve(track.id)
            val request =
                DownloadRequest.Builder(track.id, Uri.parse(uri))
                    .setCustomCacheKey(track.id)
                    .build()
            DownloadService.sendAddDownload(context, OfflineService::class.java, request, false)
        }
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "attachments-$id",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AttachmentsWorker>()
                    .setInputData(workDataOf("workId" to id))
                    .build(),
            )
    }

    suspend fun downloadAttachments(id: String) {
        val detail = dao.details(id) ?: return
        detail.attachments.forEach { attachment ->
            val file = File(offlineDir, attachment.id)
            if (!file.isFile) atomicWrite(file, readAsset(attachment.id, 64 * 1024 * 1024))
        }
        detail.work.coverId?.let { cover ->
            val target = File(offlineDir, cover)
            if (!target.isFile) {
                val custom =
                    File(customArtworkDir, cover).takeIf { it.isFile }
                        ?: File(artworkDir, cover).takeIf {
                            cover == stableId("${detail.work.id}|custom-cover") && it.isFile
                        }
                atomicWrite(target, custom?.readBytes() ?: readAsset(cover, 16 * 1024 * 1024))
            }
        }
        dao.attachmentsReady(id, true)
        storageChanged()
    }

    suspend fun setCover(work: WorkEntity, uri: Uri) =
        withContext(Dispatchers.IO) {
            val id = stableId("${work.id}|custom-cover")
            val bytes =
                context.contentResolver.openInputStream(uri)?.use {
                    boundedRead(it, 16 * 1024 * 1024)
                } ?: error("无法读取图片")
            atomicWrite(File(customArtworkDir, id), bytes)
            File(offlineDir, id).takeIf { it.isFile }?.let { atomicWrite(it, bytes) }
            dao.putWorks(listOf(work.copy(coverId = id)))
            artworkRevision.update { it + 1 }
            storageChanged()
        }

    suspend fun removeOffline(id: String) {
        val detail = dao.details(id) ?: return
        WorkManager.getInstance(context).cancelUniqueWork("attachments-$id")
        detail.tracks.forEach { track ->
            DownloadService.sendRemoveDownload(context, OfflineService::class.java, track.id, false)
        }
        detail.attachments.forEach { File(offlineDir, it.id).delete() }
        detail.work.coverId?.let { File(offlineDir, it).delete() }
        dao.attachmentsReady(id, false)
        storageChanged()
    }

    fun isOffline(detail: WorkDetails): Boolean =
        detail.work.offlineAttachments &&
            detail.tracks.all { downloads.value[it.id]?.state == Download.STATE_COMPLETED }
}

fun boundedRead(stream: java.io.InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = stream.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) break
        output.write(buffer, 0, count)
        require(output.size() <= limit) { "文件过大" }
    }
    return output.toByteArray()
}

class AttachmentsWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("workId") ?: return Result.failure()
        return try {
            (applicationContext as app.whisp.WhispApp).repository.downloadAttachments(id)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
