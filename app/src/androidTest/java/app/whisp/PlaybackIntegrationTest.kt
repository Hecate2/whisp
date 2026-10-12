// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.Manifest
import android.graphics.Bitmap
import android.os.Bundle
import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.whisp.data.*
import app.whisp.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackIntegrationTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val repo get() = (ui.activity.application as WhispApp).repository
    private lateinit var server: MockWebServer
    private val ranged = AtomicInteger()
    private val badAuth = AtomicInteger()
    private val fixture get() = File(repo.context.filesDir, "fixture")
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
    @Before fun prepare() {
        assertTrue("Push the fixture before running integration tests", fixture.isDirectory)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != Credentials.basic("listener", "test")) {
                    badAuth.incrementAndGet(); return MockResponse().setResponseCode(401)
                }
                val relative = request.requestUrl!!.pathSegments.drop(1).filter { it.isNotBlank() }.joinToString("/")
                val file = File(fixture, relative)
                if (request.method == "PROPFIND") {
                    val files = listOf(file) + file.listFiles().orEmpty().toList()
                    val body = files.joinToString("") { f ->
                        val path = f.relativeTo(fixture).path.replace(File.separatorChar, '/')
                        val url = server.url("/ASMR/").newBuilder().addPathSegments(path).apply { if (f.isDirectory) addPathSegment("") }.build()
                        """<d:response><d:href>${xml(url.encodedPath)}</d:href><d:propstat><d:prop><d:displayname>${xml(f.name)}</d:displayname><d:resourcetype>${if (f.isDirectory) "<d:collection/>" else ""}</d:resourcetype><d:getcontentlength>${f.length()}</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
                    }
                    return MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">$body</d:multistatus>")
                }
                if (!file.isFile) return MockResponse().setResponseCode(404)
                val range = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0
                if (range > 0) ranged.incrementAndGet()
                val bytes = file.readBytes()
                val response = MockResponse().setHeader("Accept-Ranges", "bytes")
                if (range >= bytes.size) return response.setResponseCode(416)
                val end = request.getHeader("Range")?.removePrefix("bytes=")?.substringAfter('-')?.toLongOrNull()
                    ?.coerceAtMost(bytes.size - 1L) ?: (bytes.size - 1L)
                if (request.getHeader("Range") != null) response.setResponseCode(206).setHeader("Content-Range", "bytes $range-$end/${bytes.size}")
                return response.setBody(Buffer().write(bytes, range.toInt(), (end - range + 1).toInt()))
            }
        }
        server.start()
        runBlocking {
            val source = SourceEntity("integration-nas", "测试 NAS", "webdav", server.url("/ASMR/").toString(), "listener", repo.secrets.encrypt("test"))
            repo.dao.putSource(source)
            repo.scan(source.id)
        }
        ui.waitUntil(20000) { repo.library.value.isNotEmpty() }
    }
    private fun waitFor(timeoutMs: Long = 20000, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (predicate()) return; Thread.sleep(100) }
        fail("Condition timed out")
    }
    private fun screenshot(name: String) {
        ui.waitForIdle()
        Thread.sleep(700)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(repo.context.filesDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun generatedAudioBackgroundSeekSubtitlesAndOfflinePlayback() {
        val detail = repo.library.value.first { it.work.rj == "RJ00000001" }
        assertEquals(1, detail.tracks.size)
        assertNotNull(detail.tracks.single().subtitleId)
        screenshot("library")
        ui.onNodeWithText("测试作品").performClick()
        ui.onNodeWithText("播放作品").assertExists()
        screenshot("detail")
        ui.onNodeWithText("播放作品").performClick()
        val future = MediaController.Builder(repo.context, SessionToken(repo.context,
            ComponentName(repo.context, PlaybackService::class.java))).buildAsync()
        val controller = future.get(20, TimeUnit.SECONDS)
        fun playing(): Boolean { var value = false; instrumentation.runOnMainSync { value = controller.isPlaying }; return value }
        waitFor { playing() }
        val rangeBeforeSeek = ranged.get()
        instrumentation.runOnMainSync { controller.seekTo(15 * 60_000L) }
        waitFor { var position = 0L; instrumentation.runOnMainSync { position = controller.currentPosition }; position >= 15 * 60_000L && playing() }
        waitFor { ranged.get() > rangeBeforeSeek }
        assertEquals(0, badAuth.get())
        ui.onNodeWithContentDescription("定时停止").assertExists()
        Thread.sleep(1200)
        screenshot("player")
        ui.onNodeWithContentDescription("定时停止").performScrollTo().performClick()
        ui.onNodeWithText("15 分钟").performClick()
        waitFor { var deadline = 0L; instrumentation.runOnMainSync { deadline = controller.sessionExtras.getLong("timerEnd") }; deadline > 0 }
        instrumentation.runOnMainSync { ui.activity.moveTaskToBack(true) }
        Thread.sleep(1500)
        assertTrue("Playback must continue with the activity in background", playing())
        instrumentation.runOnMainSync { controller.pause() }
        waitFor { !playing() }
        instrumentation.runOnMainSync { ui.activity.startActivity(android.content.Intent(ui.activity, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        Thread.sleep(1000)
        runBlocking { repo.downloadWork(detail.work.id) }
        waitFor(90000) { repo.downloads.value[detail.tracks.single().id]?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }
        waitFor(20000) { runBlocking { repo.dao.details(detail.work.id)!!.work.offlineAttachments } }
        val position = runBlocking { repo.dao.progress(detail.work.id)!! }
        assertTrue(position.positionMs >= 15 * 60_000L)
        server.shutdown()
        runBlocking {
            repo.removeSource("integration-nas")
            assertTrue(repo.dao.details(detail.work.id)!!.work.offlineAttachments)
        }
        val item = runBlocking { repo.mediaItem(detail.tracks.single(), detail.work) }
        instrumentation.runOnMainSync {
            controller.sendCustomCommand(SessionCommand(PlaybackService.TIMER, Bundle.EMPTY), Bundle().apply { putInt("minutes", 0) })
            controller.setMediaItem(item, 20 * 60_000L); controller.prepare(); controller.play()
        }
        waitFor { playing() }
        runBlocking { assertFalse(repo.readAsset(detail.tracks.single().subtitleId!!).isEmpty()) }
        instrumentation.runOnMainSync { controller.pause() }
        instrumentation.runOnMainSync { MediaController.releaseFuture(future) }
    }
    @After fun cleanup() { runCatching { server.shutdown() } }
}
