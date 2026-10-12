// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.Manifest
import android.content.ComponentName
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.whisp.data.*
import app.whisp.playback.PlaybackService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import mockwebserver3.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class AsmrOneIntegrationTest {
    @get:Rule(order = 0)
    val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private var diagnostics: () -> String = { "" }

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        return checkNotNull(result)
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < until) {
            ui.waitForIdle()
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Condition timed out: ${diagnostics()}")
    }

    @Test
    fun pagedBrowseImportRefreshPlaybackAndOfflineUseTheSharedLibrary() {
        val repo = (ui.activity.application as WhispApp).repository
        assertTrue(repo.context.packageName.endsWith(".smoketest"))
        val audio = SystemMediaIntegrationTest.silentWave(repo.context.cacheDir, 2).readBytes()
        val image =
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xffcc8899.toInt())
            }
        val png =
            ByteArrayOutputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
        image.recycle()
        val subtitle = "WEBVTT\n\n00:00.000 --> 00:39.000\n示例在线字幕\n".toByteArray()
        val leaks = AtomicInteger()
        val searches = AtomicInteger()
        val ranges = AtomicInteger()
        val revision = AtomicInteger(1)
        MockWebServer().use { server ->
            server.start()
            fun metadata(id: Long) =
                """{"id":$id,"source_id":"RJ${id.toString().padStart(8, '0')}","title":"示例在线作品$id","vas":[{"name":"示例声优"}],"mainCoverUrl":"${server.url("/cover.png")}"}"""
            fun tree() =
                """[
              {"type":"folder","title":"音频","children":[
                {"type":"audio","title":"01_示例音轨.wav","mediaStreamUrl":"${server.url("/audio.wav?revision=${revision.get()}")}","duration":40,"size":${audio.size}},
                {"type":"audio","title":"02_示例音轨.wav","mediaStreamUrl":"${server.url("/audio.wav?revision=${revision.get()}")}","duration":40,"size":${audio.size}}]},
              {"type":"folder","title":"字幕","children":[{"type":"text","title":"01_示例音轨.wav.vtt","mediaStreamUrl":"${server.url("/subtitle.vtt")}"}]}]
            """
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.headers["Authorization"] != null) leaks.incrementAndGet()
                        val path = request.url.encodedPath
                        val response = MockResponse.Builder()
                        if (path == "/api/works" || path.startsWith("/api/search/")) {
                            if (path.startsWith("/api/search/")) searches.incrementAndGet()
                            return response
                                .body(
                                    """{"works":[${metadata(1)}],"pagination":{"totalCount":1}}""",
                                )
                                .build()
                        }
                        if (path.startsWith("/api/workInfo/"))
                            return response
                                .body(metadata(request.url.pathSegments.last().toLong()))
                                .build()
                        if (path.startsWith("/api/tracks/")) return response.body(tree()).build()
                        val bytes =
                            when (path) {
                                "/audio.wav" -> audio
                                "/subtitle.vtt" -> subtitle
                                "/cover.png" -> png
                                else -> return response.code(404).build()
                            }
                        val range = request.headers["Range"]
                        val start =
                            range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        if (start >= bytes.size) return response.code(416).build()
                        val end =
                            range?.substringAfter('-')?.toIntOrNull()?.coerceAtMost(bytes.lastIndex)
                                ?: bytes.lastIndex
                        if (range != null) {
                            ranges.incrementAndGet()
                            response
                                .code(206)
                                .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                        }
                        return response
                            .setHeader("Accept-Ranges", "bytes")
                            .body(Buffer().write(bytes, start, end - start + 1))
                            .build()
                    }
                }
            val sourceId = stableId("asmr.one")
            runBlocking {
                repo.dao.putSource(
                    SourceEntity(sourceId, "测试在线来源", "asmrone", server.url("/").toString()),
                )
                repo.dao.putSource(
                    SourceEntity(
                        "nas-test",
                        "测试 NAS",
                        "webdav",
                        server.url("/").toString(),
                        "listener",
                        repo.secrets.encrypt("test"),
                    ),
                )
            }
            ui.onNodeWithText("来源", useUnmergedTree = true).performClick()
            ui.onNodeWithContentDescription("添加来源").performClick()
            ui.onNodeWithText("ASMR.ONE").performClick()
            ui.waitUntil(15000) {
                ui.onAllNodesWithContentDescription("示例在线作品1").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(runBlocking { repo.dao.allWorks().isEmpty() })
            waitFor {
                repo.artwork(stableId("${stableId("asmr.one|work|1")}|_asmr-cover/main.jpg")) !=
                    null
            }
            ui.waitForIdle()
            ui.waitUntil(10000) {
                ui.onAllNodes(
                        SemanticsMatcher.expectValue(
                            androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                            "封面已加载",
                        ),
                    )
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(repo.context.getExternalFilesDir(null), "asmr-catalog.png")
                    .outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            ui.onNodeWithContentDescription("搜索在线作品").performClick()
            ui.onNodeWithTag("asmr-search").performTextInput("示例")
            ui.onNodeWithContentDescription("提交在线搜索").performClick()
            waitFor { searches.get() > 0 }
            ui.onNodeWithContentDescription("示例在线作品1").performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithText("播放作品").fetchSemanticsNodes().isNotEmpty() }
            val workId = stableId("asmr.one|work|1")
            val detail = runBlocking { repo.dao.details(workId)!! }
            assertEquals(2, detail.tracks.size)
            assertEquals(40000L, detail.tracks.first().durationMs)
            val track = detail.tracks.first { it.fileName.startsWith("01") }
            assertNotNull(track.subtitleId)
            val uri = runBlocking { repo.resolve(track.id) }
            val spec =
                androidx.media3.datasource.DataSpec.Builder()
                    .setUri(uri)
                    .setKey(track.id)
                    .setPosition(64)
                    .setLength(128)
                    .build()
            androidx.media3.datasource
                .DataSourceInputStream(repo.playbackFactory.createDataSource(), spec)
                .use {
                    assertArrayEquals(audio.copyOfRange(64, 192), it.readBytes())
                }
            waitFor { repo.artwork(detail.work.coverId) != null }
            val future = main {
                MediaController.Builder(
                        repo.context,
                        SessionToken(
                            repo.context,
                            ComponentName(repo.context, PlaybackService::class.java),
                        ),
                    )
                    .buildAsync()
            }
            val controller = future.get(15, TimeUnit.SECONDS)
            val model = main {
                androidx.lifecycle
                    .ViewModelProvider(ui.activity)[app.whisp.ui.AppViewModel::class.java]
            }
            diagnostics = {
                main {
                    "state=${controller.playbackState}, ready=${controller.playWhenReady}, count=${controller.mediaItemCount}, error=${controller.playerError?.errorCodeName}, opening=${model.openingTrackId.value}, uiError=${model.error.value}"
                }
            }
            try {
                ui.waitForIdle()
                ui.onNodeWithText("播放作品").assertIsDisplayed()
                ui.onNodeWithText("播放作品").performClick()
                waitFor { main { controller.isPlaying } }
                ui.waitUntil(10000) {
                    ui.onAllNodesWithText("示例在线字幕").fetchSemanticsNodes().isNotEmpty()
                }
                main { controller.pause() }
                waitFor { !main { controller.isPlaying } }
                runBlocking {
                    repo.flushPlaybackProgress()
                    repo.dao.putWorks(listOf(detail.work.copy(title = "自定义标题", favorite = true)))
                    repo.dao.saveBookmark(
                        BookmarkEntity("bookmark-test", workId, track.id, 1000, "示例书签"),
                    )
                    revision.set(2)
                    repo.importAsmrWork(sourceId, 2)
                    repo.importAsmrWork(sourceId, 1)
                    val refreshed = repo.dao.details(workId)!!
                    assertEquals("自定义标题", refreshed.work.title)
                    assertTrue(refreshed.work.favorite)
                    assertEquals(
                        detail.tracks.map { it.id }.toSet(),
                        refreshed.tracks.map { it.id }.toSet(),
                    )
                    assertNotNull(repo.dao.progress(workId))
                    assertTrue(repo.dao.locations(track.id).single().uri.endsWith("revision=2"))
                    assertFalse(
                        repo.dao
                            .locations(
                                repo.dao.details(stableId("asmr.one|work|2"))!!.tracks.first().id,
                            )
                            .isEmpty(),
                    )
                    repo.downloadWork(workId)
                }
                waitFor {
                    detail.tracks.all {
                        repo.downloads.value[it.id]?.state == Download.STATE_COMPLETED
                    }
                }
                waitFor { runBlocking { repo.dao.details(workId)!!.work.offlineAttachments } }
                assertEquals(0, leaks.get())
                assertTrue(ranges.get() > 0)
                server.close()
                runBlocking {
                    repo.removeSource(sourceId)
                    assertArrayEquals(subtitle, repo.readAsset(track.subtitleId!!))
                }
                val offline = runBlocking { repo.mediaItem(track, detail.work) }
                main {
                    controller.setMediaItem(offline, 1000)
                    controller.prepare()
                    controller.play()
                }
                waitFor { main { controller.isPlaying } }
                main { controller.pause() }
            } finally {
                main { MediaController.releaseFuture(future) }
            }
        }
    }
}
