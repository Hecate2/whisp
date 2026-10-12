// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class StorageIntegrationTest {
    @Test
    fun migrationCacheRecoveryAndStorageControlsPreserveDurableData() = runBlocking {
        val repo =
            (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                    as WhispApp)
                .repository
        assertTrue(repo.context.packageName.endsWith(".smoketest"))
        repo.storageUsage() // Await initial migration, then emulate the previous version's files.
        val bitmap = Bitmap.createBitmap(2200, 1700, Bitmap.Config.ARGB_8888)
        val random = Random(18)
        val pixels = IntArray(2200 * 1700) { 0xff000000.toInt() or random.nextInt(0xffffff) }
        bitmap.setPixels(pixels, 0, 2200, 0, 0, 2200, 1700)
        val original =
            ByteArrayOutputStream()
                .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        bitmap.recycle()
        val sourceFile =
            File(repo.context.filesDir, "test-original.png").apply { writeBytes(original) }
        val customId = stableId("custom-work|custom-cover")
        val legacy = File(repo.context.filesDir, "artwork").apply { mkdirs() }
        File(legacy, "remote-cover").writeBytes(original)
        File(legacy, customId).writeBytes(original)
        val customBytes = original.copyOf()
        val offline = File(repo.context.filesDir, "offline/attachments").apply { mkdirs() }
        File(offline, "pinned").writeBytes(byteArrayOf(11, 12))
        File(repo.context.cacheDir, "preview").writeBytes(original)
        val unknown = File(repo.context.cacheDir, "unrelated").apply { writeText("keep") }
        repo.dao.putSource(
            SourceEntity(
                "local-source",
                "Storage",
                "local",
                Uri.fromFile(sourceFile).toString(),
                secret = "unchanged",
            ),
        )
        repo.dao.putWorks(
            listOf(
                WorkEntity(
                    "remote-work",
                    null,
                    "Storage remote",
                    "Storage remote",
                    coverId = "remote-cover",
                    favorite = true,
                    offlineAttachments = true,
                ),
                WorkEntity(
                    "custom-work",
                    null,
                    "Storage custom",
                    "Storage custom",
                    coverId = customId,
                    offlineAttachments = true,
                ),
            ),
        )
        repo.dao.putAttachments(
            listOf(
                AttachmentEntity(
                    "preview",
                    "remote-work",
                    "Preview",
                    "preview.png",
                    "preview.png",
                    "image",
                ),
            ),
        )
        repo.dao.putLocations(
            listOf(
                LocationEntity(
                    "cover-location",
                    "remote-cover",
                    "local-source",
                    Uri.fromFile(sourceFile).toString(),
                    original.size.toLong(),
                ),
                LocationEntity(
                    "preview-location",
                    "preview",
                    "local-source",
                    Uri.fromFile(sourceFile).toString(),
                    original.size.toLong(),
                ),
            ),
        )
        repo.dao.saveProgress(ProgressEntity("remote-work", "track", 12345))
        repo.dao.saveBookmark(BookmarkEntity("bookmark", "remote-work", "track", 54321, "keep"))
        repo.migrateImages()
        assertFalse(File(legacy, "remote-cover").exists())
        assertFalse(File(legacy, customId).exists())
        val remote = repo.artwork("remote-cover")!!
        assertTrue(remote.path.contains("/cache/artwork/"))
        assertTrue(remote.length() < original.size)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(remote.path, bounds)
        assertEquals(1600, maxOf(bounds.outWidth, bounds.outHeight))
        assertArrayEquals(customBytes, repo.artwork(customId)!!.readBytes())
        val preview = repo.previewImage("preview")
        assertTrue(preview.path.contains("/cache/previews/"))
        assertFalse(File(repo.context.cacheDir, "preview").exists())
        val small = byteArrayOf(1, 2, 3)
        assertArrayEquals(small, ImageOptimizer.optimize(small))
        // Non-image data must not be rewritten during a resumable migration.
        assertArrayEquals(ByteArray(300000), ImageOptimizer.optimize(ByteArray(300000)))
        repo.clearTemporaryCache()
        assertEquals(0, repo.storageUsage().cache)
        assertArrayEquals(customBytes, repo.artwork(customId)!!.readBytes())
        assertArrayEquals(byteArrayOf(11, 12), File(offline, "pinned").readBytes())
        assertEquals("keep", unknown.readText())
        assertEquals("unchanged", repo.dao.source("local-source")!!.secret)
        assertTrue(repo.dao.work("remote-work")!!.favorite)
        assertEquals(12345, repo.dao.latestProgress()!!.positionMs)
        assertEquals(1, repo.bookmarks.value.size)
        // Recovery needs no rescan; concurrent consumers share one fetch/result.
        val restored = coroutineScope {
            (1..8).map { async { repo.ensureArtwork("remote-cover") } }.awaitAll()
        }
        assertTrue(restored.all { it!!.isFile })
        assertEquals(1, File(repo.context.cacheDir, "artwork").listFiles()!!.size)
        assertNotNull(repo.previewImage("preview"))
        repo.clearTemporaryCache()
        // Navigating away cancels the UI consumer, not the shared fetch. It must not
        // leave a completed request pointing at a file deleted by a subsequent clear.
        coroutineScope {
            val consumer =
                launch(start = CoroutineStart.UNDISPATCHED) { repo.ensureArtwork("remote-cover") }
            consumer.cancelAndJoin()
        }
        repo.clearTemporaryCache()
        assertTrue(
            "Canceled consumers must not break cover recovery",
            repo.ensureArtwork("remote-cover")!!.isFile,
        )
        // Pinned audio lives in the separate download cache and is not cleared.
        fun put(cache: SimpleCache, key: String, bytes: ByteArray) {
            val hole = cache.startReadWrite(key, 0, bytes.size.toLong())
            try {
                val f = cache.startFile(key, 0, bytes.size.toLong())
                f.writeBytes(bytes)
                cache.commitFile(f, bytes.size.toLong())
            } finally {
                cache.releaseHoleSpan(hole)
            }
        }
        put(repo.downloadCache, "pinned-audio", ByteArray(100))
        repo.clearTemporaryCache()
        assertEquals(100, repo.downloadCache.cacheSpace)
        // Real Media3 files: resize applies immediately, touched spans survive LRU eviction.
        val evictor = AdjustableCacheEvictor(250)
        val cache =
            SimpleCache(
                File(repo.context.cacheDir, "lru-test"),
                evictor,
                StandaloneDatabaseProvider(repo.context),
            )
        try {
            put(cache, "a", ByteArray(100))
            put(cache, "b", ByteArray(100))
            Thread.sleep(5)
            cache.startReadWrite("a", 0, 100)
            put(cache, "c", ByteArray(100))
            assertTrue(cache.isCached("a", 0, 100))
            assertFalse(cache.isCached("b", 0, 100))
            evictor.resize(cache, 100)
            assertEquals(100, cache.cacheSpace)
            evictor.resize(cache, 64)
            assertEquals(0, cache.cacheSpace)
        } finally {
            cache.release()
        }
        Unit
    }
}

@RunWith(AndroidJUnit4::class)
class StorageUiIntegrationTest {
    @get:Rule
    val permission =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun storageControlsPersistAcrossActivityRecreation() {
        val repo = (ui.activity.application as WhispApp).repository
        assertTrue(repo.context.packageName.endsWith(".smoketest"))
        val device =
            androidx.test.uiautomator.UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation(),
            )
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ui.activity
                .getSystemService(android.app.ActivityManager::class.java)
                .appTasks
                .first { it.taskInfo.taskId == ui.activity.taskId }
                .moveToFront()
        }
        assertTrue(
            device.wait(
                androidx.test.uiautomator.Until.hasObject(
                    androidx.test.uiautomator.By.pkg(repo.context.packageName),
                ),
                5000,
            ),
        )
        ui.onNodeWithContentDescription("来源").performClick()
        ui.onNodeWithContentDescription("存储管理").performClick()
        ui.onNodeWithTag("storage-page").assertIsDisplayed()
        val preview =
            File(repo.context.filesDir, "storage-ui-fixture").apply {
                writeBytes(ByteArray(21 * 1024))
            }
        runBlocking {
            repo.dao.putSource(
                SourceEntity(
                    "storage-ui",
                    "Storage UI",
                    "local",
                    preview.parentFile!!.toURI().toString(),
                ),
            )
            repo.dao.putLocations(
                listOf(
                    LocationEntity(
                        "storage-ui-location",
                        "storage-ui-preview",
                        "storage-ui",
                        preview.toURI().toString(),
                        preview.length(),
                    ),
                ),
            )
            repo.previewImage("storage-ui-preview")
        }
        ui.waitUntil(5000) {
            ui.mainClock.advanceTimeBy(400)
            ui.onAllNodesWithText("21 KiB").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("cache-limit-64").performClick()
        ui.waitUntil(5000) { repo.settings.value.streamingCacheMiB == 64 }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("cache-limit-64").assertIsSelected()
        ui.onNodeWithTag("clear-cache").performClick()
        ui.onNodeWithText("自定义封面").assertExists()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            File(repo.context.getExternalFilesDir(null), "storage.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }
}

@RunWith(AndroidJUnit4::class)
class ArtworkCacheReloadIntegrationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun visibleCoverRecoversWhenEvictedWithoutChangingThePage() {
        val repo = (ui.activity.application as WhispApp).repository
        assertTrue(repo.context.packageName.endsWith(".smoketest"))
        val image =
            Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xffcc8899.toInt())
            }
        val original = File(repo.context.filesDir, "reload-source.png")
        original.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        runBlocking {
            repo.storageUsage()
            repo.dao.putSource(
                SourceEntity("reload-source", "Reload", "local", Uri.fromFile(original).toString()),
            )
            repo.dao.putLocations(
                listOf(
                    LocationEntity(
                        "reload-location",
                        "reload-cover",
                        "reload-source",
                        Uri.fromFile(original).toString(),
                        original.length(),
                    ),
                ),
            )
            repo.dao.putWorks(
                listOf(
                    WorkEntity(
                        "reload-work",
                        null,
                        "Cache Artwork",
                        "Cache Artwork",
                        coverId = "reload-cover",
                        offlineAttachments = true,
                    ),
                ),
            )
        }
        ui.waitUntil(10000) { repo.artwork("reload-cover")?.isFile == true }
        ui.onNodeWithContentDescription("Cache Artwork").assertIsDisplayed()
        assertTrue(repo.artwork("reload-cover")!!.delete())
        repo.artworkRevision.value +=
            1 // A newly cached image publishes this after LRU evicts an older one.
        ui.waitUntil(10000) { repo.artwork("reload-cover")?.isFile == true }
        assertEquals(
            0xffcc8899.toInt(),
            BitmapFactory.decodeFile(repo.artwork("reload-cover")!!.path).let { bitmap ->
                val color = bitmap.getPixel(0, 0)
                bitmap.recycle()
                color
            },
        )
        ui.onNodeWithContentDescription("Cache Artwork").assertIsDisplayed()
    }
}
