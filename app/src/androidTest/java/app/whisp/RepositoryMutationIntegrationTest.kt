// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.AttachmentEntity
import app.whisp.data.LibraryRepository
import app.whisp.data.LocationEntity
import app.whisp.data.SourceEntity
import app.whisp.data.WorkEntity
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class RepositoryMutationIntegrationTest {
    private fun repository(): LibraryRepository =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WhispApp)
            .repository
            .also { assertTrue(it.context.packageName.endsWith(".smoketest")) }

    @Test
    fun sourcePasswordWhitespaceIsPreservedByTheKeystore() {
        val secrets = repository().secrets
        val password = " \t "
        val encrypted = secrets.encrypt(password)
        assertTrue(encrypted.isNotEmpty())
        assertNotEquals(password, encrypted)
        assertEquals(password, secrets.decrypt(encrypted))
        assertEquals("", secrets.encrypt(""))
        assertEquals("", secrets.decrypt(""))
    }

    @Test
    fun fourIntroductionImagesCanStartBeforeAnyResponseCompletes() = runBlocking {
        val repo = repository()
        val arrived = CountDownLatch(4)
        val release = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    arrived.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    return MockResponse.Builder().body("synthetic image bytes").build()
                }
            }

        server.start()
        try {
            coroutineScope {
                val requests =
                    (1..4).map { index ->
                        async(Dispatchers.IO) {
                            repo.ensureDlsiteImage(
                                "introduction-concurrency-$index",
                                server.url("/$index").toString(),
                            )
                        }
                    }
                try {
                    assertTrue(
                        "All four image lanes must reach the server",
                        arrived.await(5, TimeUnit.SECONDS),
                    )
                } finally {
                    release.countDown()
                }
                assertTrue(requests.awaitAll().all { it?.isFile == true })
            }
        } finally {
            release.countDown()
            server.close()
            repo.clearTemporaryCache()
        }
    }

    @Test fun finishingScanPreservesNewSourceConfiguration() = sourceScanRace(false, 207)

    @Test fun failedScanPreservesNewSourceConfiguration() = sourceScanRace(false, 500)

    @Test fun finishingScanDoesNotRestoreRemovedSource() = sourceScanRace(true, 207)

    @Test fun failedScanDoesNotRestoreRemovedSource() = sourceScanRace(true, 500)

    private fun sourceScanRace(remove: Boolean, status: Int) = runBlocking {
        val repo = repository()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    return MockResponse.Builder()
                        .code(status)
                        .body(
                            """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/01.m4a</d:href><d:propstat><d:prop><d:displayname>01.m4a</d:displayname><d:getcontentlength>128</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
                        )
                        .build()
                }
            }
        server.start()
        val id = "scan-race-$remove-$status"
        repo.dao.putSource(SourceEntity(id, "Before", "webdav", server.url("/dav/").toString()))
        try {
            coroutineScope {
                val scan =
                    async(Dispatchers.IO) {
                        try {
                            repo.scan(id)
                        } catch (_: IOException) {} catch (_: IllegalArgumentException) {}
                    }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    if (remove) repo.removeSource(id)
                    else
                        repo.dao.putSource(
                            repo.dao
                                .source(id)!!
                                .copy(
                                    name = "After",
                                    secret = repo.secrets.encrypt("synthetic-new-password"),
                                ),
                        )
                } finally {
                    release.countDown()
                }
                withTimeout(10_000) { scan.await() }
            }
            if (remove) {
                assertNull(
                    "An in-flight scan must not restore a deleted source",
                    repo.dao.source(id),
                )
                assertTrue(repo.dao.sourceWorkIds(id).isEmpty())
            } else {
                val latest = repo.dao.source(id)!!
                assertEquals("After", latest.name)
                assertEquals("synthetic-new-password", repo.secrets.decrypt(latest.secret))
            }
        } finally {
            release.countDown()
            repo.removeSource(id)
            server.close()
        }
    }

    @Test
    fun coverChangePreservesMetadataWrittenSinceEditorOpened() = runBlocking {
        val repo = repository()
        val original = WorkEntity("cover-editor-race", null, "Before", "Before")
        repo.dao.putWorks(listOf(original))
        repo.dao.putWorks(
            listOf(original.copy(title = "After", favorite = true, origin = "dlsite")),
        )
        val picture =
            java.io.File(repo.context.cacheDir, "synthetic-cover.png").apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
        try {
            repo.setCover(original, android.net.Uri.fromFile(picture))
            val latest = repo.dao.work(original.id)!!
            assertEquals("After", latest.title)
            assertTrue(latest.favorite)
            assertEquals("dlsite", latest.origin)
            assertNotNull(latest.coverId)
        } finally {
            repo.removeAsmrWork(original.id)
            picture.delete()
        }
    }

    @Test
    fun removingOfflineCopyWaitsForInFlightAttachmentWriter() = runBlocking {
        val repo = repository()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    return MockResponse.Builder().body("synthetic attachment").build()
                }
            }
        server.start()
        val sourceId = "attachment-removal-source"
        val workId = "attachment-removal-work"
        val assetId = "attachment-removal-asset"
        repo.dao.putSource(
            SourceEntity(sourceId, "Attachments", "webdav", server.url("/").toString()),
        )
        repo.dao.putWorks(
            listOf(WorkEntity(workId, null, "Attachments", "Attachments", origin = "dlsite")),
        )
        repo.dao.putAttachments(
            listOf(
                AttachmentEntity(assetId, workId, "Script", "script.txt", "script.txt", "script"),
            ),
        )
        repo.dao.putLocations(
            listOf(
                LocationEntity(
                    "attachment-removal-location",
                    assetId,
                    sourceId,
                    server.url("/script.txt").toString(),
                    20,
                ),
            ),
        )
        try {
            coroutineScope {
                val writer = async(Dispatchers.IO) { repo.downloadAttachments(workId) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val removal = async(Dispatchers.IO) { repo.removeOffline(workId) }
                delay(100)
                release.countDown()
                withTimeout(10_000) { awaitAll(writer, removal) }
            }
            assertFalse(repo.dao.work(workId)!!.offlineAttachments)
            assertFalse(
                java.io.File(repo.context.filesDir, "offline/attachments/$assetId").exists(),
            )
        } finally {
            release.countDown()
            repo.removeAsmrWork(workId)
            repo.removeSource(sourceId)
            server.close()
        }
    }

    @Test
    fun removingOneWorkPreservesAnOfflineCoverSharedByAnotherAccount() = runBlocking {
        val repo = repository()
        val coverId = "shared-offline-cover"
        val first =
            WorkEntity(
                "shared-cover-work-1",
                null,
                "First",
                "First",
                coverId = coverId,
                offlineAttachments = true,
                origin = "dlsite",
            )
        val second = first.copy(id = "shared-cover-work-2", title = "Second")
        repo.dao.putWorks(listOf(first, second))
        val cover =
            java.io.File(repo.context.filesDir, "offline/attachments/$coverId").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
        try {
            repo.removeOffline(first.id)
            assertTrue("The second account still owns the pinned cover", cover.isFile)
            assertTrue(repo.dao.work(second.id)!!.offlineAttachments)
            repo.removeAsmrWork(first.id)
            assertTrue("Deleting the first work must keep the second work's cover", cover.isFile)
            repo.removeAsmrWork(second.id)
            assertFalse("The final owner's removal releases the offline file", cover.exists())
        } finally {
            repo.removeAsmrWork(first.id)
            repo.removeAsmrWork(second.id)
            cover.delete()
        }
    }

    @Test
    fun sourceRescanKeepsIdentityProgressAndFavoritesWhenTracksAreAddedOrRemoved() = runBlocking {
        val repo = repository()
        val server = MockWebServer()
        server.start()
        val sourceId = "stable-rescan-source"
        fun listing(vararg names: String) =
            MockResponse.Builder()
                .code(207)
                .body(
                    "<d:multistatus xmlns:d=\"DAV:\">" +
                        names.joinToString("") { name ->
                            "<d:response><d:href>/dav/RJ00000001%20Example/$name</d:href><d:propstat><d:prop><d:displayname>$name</d:displayname><d:getcontentlength>128</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                        } +
                        "</d:multistatus>",
                )
                .build()
        repo.dao.putSource(
            SourceEntity(sourceId, "Rescan", "webdav", server.url("/dav/").toString()),
        )
        var workId: String? = null
        try {
            server.enqueue(listing("01.m4a"))
            repo.scan(sourceId)
            workId = repo.dao.sourceWorkIds(sourceId).single()
            val first = repo.dao.details(workId)!!
            val firstTrack = first.tracks.single()
            repo.dao.toggleFavorite(workId)
            repo.dao.updateWorkIdentity(workId, "Edited title", "Edited artist")
            repo.dao.updateDuration(firstTrack.id, 65000)
            repo.dao.saveProgress(app.whisp.data.ProgressEntity(workId, firstTrack.id, 12345))
            server.enqueue(listing("01.m4a", "02.m4a"))
            repo.scan(sourceId)
            assertEquals(listOf(workId), repo.dao.sourceWorkIds(sourceId))
            val added = repo.dao.details(workId)!!
            assertEquals(2, added.tracks.size)
            assertTrue(added.work.favorite)
            assertEquals("Edited title", added.work.title)
            assertEquals("Edited artist", added.work.artist)
            assertEquals(65000L, added.tracks.single { it.id == firstTrack.id }.durationMs)
            assertEquals(12345L, repo.dao.progress(workId)!!.positionMs)
            server.enqueue(listing("01.m4a"))
            repo.scan(sourceId)
            val removed = repo.dao.details(workId)!!
            assertEquals(listOf(firstTrack.id), removed.tracks.map { it.id })
            assertTrue(removed.work.favorite)
            assertEquals(12345L, repo.dao.progress(workId)!!.positionMs)
            server.enqueue(listing())
            repo.scan(sourceId)
            assertTrue(repo.dao.sourceWorkIds(sourceId).isEmpty())
            assertTrue(repo.dao.details(workId)!!.tracks.isEmpty())
            assertTrue(repo.dao.work(workId)!!.favorite)
        } finally {
            repo.removeSource(sourceId)
            workId?.let {
                repo.dao.deleteWorkTracks(it)
                repo.dao.deleteWorkProgress(it)
                repo.dao.deleteWork(it)
            }
            server.close()
        }
    }

    @Test
    fun refreshingAWorkReleasesRemovedUnfinishedDownloads() = runBlocking {
        val repo = repository()
        val sourceId = "obsolete-download-source"
        val server = MockWebServer()
        server.start()
        val work = app.whisp.data.AsmrWork(98, "Download test", "RJ00000098", "Example", null)
        val old =
            app.whisp.data.TrackEntity(
                "obsolete-download-track",
                work.workId,
                "Old",
                "old.wav",
                "old.wav",
                1,
            )
        val current =
            old.copy(
                id = "current-download-track",
                title = "Current",
                fileName = "current.wav",
                relativePath = "current.wav",
            )
        repo.dao.putSource(
            SourceEntity(sourceId, "Download test", "asmrone", server.url("/").toString()),
        )
        repo.dao.putWorks(listOf(work.entity()))
        repo.dao.putTracks(listOf(old))
        repo.dao.putLocations(
            listOf(
                LocationEntity(
                    "obsolete-location",
                    old.id,
                    sourceId,
                    server.url("/old.wav").toString(),
                    20,
                ),
            ),
        )
        val request =
            androidx.media3.exoplayer.offline.DownloadRequest.Builder(
                    old.id,
                    android.net.Uri.parse("whisp-asmr://$sourceId/${old.id}"),
                )
                .setCustomCacheKey(old.id)
                .build()
        (repo.downloadManager.downloadIndex
                as androidx.media3.exoplayer.offline.WritableDownloadIndex)
            .putDownload(
                androidx.media3.exoplayer.offline.Download(
                    request,
                    androidx.media3.exoplayer.offline.Download.STATE_STOPPED,
                    1,
                    1,
                    20,
                    1,
                    0,
                    androidx.media3.exoplayer.offline.DownloadProgress().apply {
                        bytesDownloaded = 3
                    },
                ),
            )
        val hole = repo.downloadCache.startReadWrite(old.id, 0, 3)
        try {
            val file = repo.downloadCache.startFile(old.id, 0, 3)
            file.writeBytes(byteArrayOf(1, 2, 3))
            repo.downloadCache.commitFile(file, 3)
        } finally {
            repo.downloadCache.releaseHoleSpan(hole)
        }
        try {
            val indexed =
                app.whisp.data.IndexedLibrary(
                    listOf(work.entity()),
                    listOf(current),
                    emptyList(),
                    listOf(
                        LocationEntity(
                            "current-location",
                            current.id,
                            sourceId,
                            server.url("/current.wav").toString(),
                            20,
                        ),
                    ),
                )
            repo.addAsmrPreview(app.whisp.data.AsmrPreview(sourceId, work.artist, work, indexed))
            assertNull(repo.dao.track(old.id))
            withTimeout(15_000) {
                while (repo.downloadManager.downloadIndex.getDownload(old.id) != null) delay(50)
            }
            assertFalse(repo.downloadCache.keys.contains(old.id))
        } finally {
            repo.removeAsmrWork(work.workId)
            repo.removeSource(sourceId)
            server.close()
        }
    }
}
