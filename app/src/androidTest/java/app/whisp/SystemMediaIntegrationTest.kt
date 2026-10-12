// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.*
import app.whisp.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SystemMediaIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repo get() = (context.applicationContext as WhispApp).repository
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun browser(): MediaBrowser = main {
        MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    }.get(20, TimeUnit.SECONDS)

    private fun seed(remote: Boolean): List<TrackEntity> {
        assertTrue(context.packageName.endsWith(".smoketest"))
        val tracks = (1..2).map { TrackEntity("system-$it", "system-work", "Track $it", "$it.wav", "audio/$it.wav", it, 40000) }
        val wave = silentWave(context.filesDir)
        val cover = File(context.filesDir, "custom-artwork/system-cover").apply { parentFile!!.mkdirs() }
        Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.rgb(24, 228, 71))
            cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        runBlocking {
            repo.setSettings(hide = false, hideRecents = false, touchFeedback = true)
            repo.dao.putWorks(listOf(WorkEntity("system-work", null, "System test", "System test", coverId = "system-cover")))
            repo.dao.putTracks(tracks)
            repo.dao.putSource(SourceEntity("system-source", "System", if (remote) "smb" else "local",
                if (remote) "smb://203.0.113.1/data/" else wave.parentFile!!.toURI().toString()))
            repo.dao.putLocations(tracks.map { LocationEntity("system-location-${it.id}", it.id, "system-source",
                if (remote) SmbAddress.assetUri("system-source", it.relativePath) else wave.toURI().toString(), wave.length()) })
            repo.dao.saveProgress(ProgressEntity("system-work", tracks[1].id, 12345))
        }
        return tracks
    }

    @Test fun media3AndLegacyBrowsersReadLocalPrivateResumeMetadataWithoutOpeningNas() {
        val tracks = seed(remote = true)
        val browser = browser()
        try {
            val params = MediaLibraryService.LibraryParams.Builder().setRecent(true).build()
            val root = main { browser.getLibraryRoot(params) }.get(10, TimeUnit.SECONDS)
            assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
            assertTrue(root.value!!.mediaMetadata.isBrowsable == true)
            val children = main { browser.getChildren(root.value!!.mediaId, 0, 10, params) }.get(10, TimeUnit.SECONDS)
            assertEquals("Recent browsing must succeed", LibraryResult.RESULT_SUCCESS, children.resultCode)
            val item = children.value!!.single()
            assertEquals(tracks[1].id, item.mediaId)
            assertTrue(item.mediaMetadata.isPlayable == true)
            assertNotNull(item.mediaMetadata.artworkData)
            assertTrue(item.mediaMetadata.artworkData!!.size < 65536)
            assertNull("Browsing must not resolve an audio URI", item.localConfiguration)
            main { assertEquals(Player.STATE_IDLE, browser.playbackState); assertFalse(browser.playWhenReady); assertEquals(0, browser.mediaItemCount) }

            val connected = CompletableFuture<String>()
            lateinit var legacy: android.media.browse.MediaBrowser
            main {
                legacy = android.media.browse.MediaBrowser(context, ComponentName(context, PlaybackService::class.java),
                    object : android.media.browse.MediaBrowser.ConnectionCallback() {
                        override fun onConnected() { connected.complete(legacy.root) }
                        override fun onConnectionFailed() { connected.completeExceptionally(AssertionError("Legacy browser refused")) }
                    }, Bundle().apply { putBoolean("android.service.media.extra.RECENT", true) })
                legacy.connect()
            }
            try {
                val rootId = connected.get(10, TimeUnit.SECONDS)
                val loaded = CompletableFuture<List<android.media.browse.MediaBrowser.MediaItem>>()
                val hiddenLegacy = CompletableFuture<android.media.browse.MediaBrowser.MediaItem>()
                main { legacy.subscribe(rootId, object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: MutableList<android.media.browse.MediaBrowser.MediaItem>) {
                        loaded.complete(children)
                        children.firstOrNull()?.takeIf { it.description.title.toString() == "正在收听" }?.let { hiddenLegacy.complete(it) }
                    }
                    override fun onError(parentId: String) { loaded.completeExceptionally(AssertionError("Legacy children failed")) }
                }) }
                val legacyItem = loaded.get(10, TimeUnit.SECONDS).single()
                assertTrue(legacyItem.isPlayable)
                assertEquals(tracks[1].id, legacyItem.mediaId)
                assertNotNull("SystemUI must receive readable artwork", legacyItem.description.iconBitmap)
                // Platform MediaBrowser does not register Media3 subscriptions. A new load
                // reads the updated preference; the OS controls when a historical card reloads.
                main { legacy.unsubscribe(rootId) }
                runBlocking { repo.setSettings(hide = true) }
                main { legacy.subscribe(rootId, object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: MutableList<android.media.browse.MediaBrowser.MediaItem>) {
                        children.firstOrNull()?.let { hiddenLegacy.complete(it) }
                    }
                    override fun onError(parentId: String) { hiddenLegacy.completeExceptionally(AssertionError("Legacy private reload failed")) }
                }) }
                val privateLegacy = hiddenLegacy.get(10, TimeUnit.SECONDS)
                assertEquals("正在收听", privateLegacy.description.title.toString())
                assertNull("Reloaded system cards must respect privacy", privateLegacy.description.iconBitmap)
            } finally { main { legacy.disconnect() } }

            runBlocking { repo.setSettings(hide = true) }
            val hidden = main { browser.getChildren(root.value!!.mediaId, 0, 10, params) }.get(10, TimeUnit.SECONDS).value!!.single()
            assertEquals("正在收听", hidden.mediaMetadata.title.toString())
            assertNull(hidden.mediaMetadata.artworkData)
            assertNull(hidden.mediaMetadata.artworkUri)
            assertNull(hidden.localConfiguration)
            val page = main { browser.getChildren(root.value!!.mediaId, 1, 10, params) }.get(10, TimeUnit.SECONDS)
            assertTrue(page.value!!.isEmpty())
        } finally { main { browser.release() }; context.stopService(Intent(context, PlaybackService::class.java)) }
    }

    @Test fun mediaButtonsRestoreQueueAndHeadlessPrivacyKeepsPositionAndLoop() {
        val tracks = seed(remote = false)
        Thread.sleep(200)
        @Suppress("DEPRECATION")
        val running = context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        assertFalse("The media button must start an absent playback service", running.any { it.service.className == PlaybackService::class.java.name })
        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(context,
            "androidx.media3.session.MediaButtonReceiver")).putExtra(Intent.EXTRA_KEY_EVENT,
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)))
        Thread.sleep(200)
        val browser = browser()
        fun waitFor(message: String, condition: () -> Boolean) {
            val end = System.currentTimeMillis() + 15000
            while (System.currentTimeMillis() < end) {
                if (main(condition)) return
                Thread.sleep(100)
            }
            fail(message)
        }
        try {
            main { browser.volume = 0f }
            waitFor("Media button must restore the saved queue") {
                browser.mediaItemCount == 2 && browser.currentMediaItem?.mediaId == tracks[1].id && browser.playbackState == Player.STATE_READY
            }
            main { browser.pause() }
            waitFor("Saved position must be restored") { browser.currentPosition in 12345..20000 }
            main {
                browser.seekTo(14000)
                browser.sendCustomCommand(SessionCommand(PlaybackService.LOOP, Bundle.EMPTY),
                    Bundle().apply { putLong("start", 10000); putLong("end", 30000) })
            }
            waitFor("Loop must be enabled") { browser.sessionExtras.getLong("loopStart", -1) == 10000L }
            runBlocking { repo.setSettings(hide = true) }
            waitFor("Privacy must update with no Activity") { browser.mediaMetadata.title.toString() == "正在收听" }
            main {
                assertEquals(14000L, browser.currentPosition)
                assertEquals(10000L, browser.sessionExtras.getLong("loopStart", -1))
                assertFalse(browser.playWhenReady)
                assertEquals(2, browser.mediaItemCount)
                assertNull(browser.mediaMetadata.artworkUri)
            }
            runBlocking { repo.setSettings(hide = false) }
            waitFor("Visible metadata must be restored") { browser.mediaMetadata.title.toString() == "Track 2" }
            // Library clients can also request a playable item by ID only.
            main { browser.setMediaItem(MediaItem.Builder().setMediaId(tracks[0].id).build()); browser.prepare() }
            waitFor("An ID-only item must resolve to indexed audio") {
                browser.currentMediaItem?.mediaId == tracks[0].id && browser.playbackState == Player.STATE_READY
            }
        } finally { main { browser.pause(); browser.clearMediaItems(); browser.release() }; context.stopService(Intent(context, PlaybackService::class.java)) }
    }

    companion object {
        /** Zero PCM samples guarantee silence, including media-button playback. */
        internal fun silentWave(directory: File): File {
            val samples = 16000 * 40
            val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(samples * 2)
            return File(directory, "system-silence.wav").apply { writeBytes(buffer.array()) }
        }
    }
}
