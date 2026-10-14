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
import java.util.concurrent.atomic.AtomicBoolean
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

    private fun capture(name: String) {
        val repo = (ui.activity.application as WhispApp).repository
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            File(repo.context.getExternalFilesDir(null), name).outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }

    @Test
    fun deletionCancelsAnAttachmentWorkerBeforeWaitingForItsLock() {
        val repo = (ui.activity.application as WhispApp).repository
        assertTrue(repo.context.packageName.endsWith(".smoketest"))
        val reading = AtomicInteger()
        MockWebServer().use { server ->
            server.start()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        reading.incrementAndGet()
                        return MockResponse.Builder()
                            .body(Buffer().write(ByteArray(64)))
                            .throttleBody(1, 1, TimeUnit.SECONDS)
                            .build()
                    }
                }
            val work = AsmrWork(99, "慢速示例作品", "RJ00000099", "示例声优", null).entity()
            val sourceId = "slow-online-source"
            val attachmentId = stableId("${work.id}|sample.png")
            runBlocking {
                repo.dao.putSource(
                    SourceEntity(sourceId, "慢速测试来源", "asmrone", server.url("/").toString()),
                )
                repo.dao.putWorks(listOf(work))
                repo.dao.putAttachments(
                    listOf(
                        AttachmentEntity(
                            attachmentId,
                            work.id,
                            "示例图片",
                            "sample.png",
                            "sample.png",
                            "image",
                        ),
                    ),
                )
                repo.dao.putLocations(
                    listOf(
                        LocationEntity(
                            "slow-location",
                            attachmentId,
                            sourceId,
                            server.url("/sample.png").toString(),
                            64,
                        ),
                    ),
                )
            }
            val manager = androidx.work.WorkManager.getInstance(repo.context)
            val request =
                androidx.work
                    .OneTimeWorkRequestBuilder<AttachmentsWorker>()
                    .setInputData(androidx.work.workDataOf("workId" to work.id))
                    .build()
            manager.enqueueUniqueWork(
                "attachments-${work.id}",
                androidx.work.ExistingWorkPolicy.KEEP,
                request,
            )
            waitFor { reading.get() > 0 }
            // The server needs over a minute to finish; removal must cancel it promptly.
            runBlocking {
                kotlinx.coroutines.withTimeout(5000) { repo.removeAsmrWork(work.id) }
                assertNull(repo.dao.work(work.id))
                assertTrue(repo.dao.locations(attachmentId).isEmpty())
                assertFalse(
                    File(repo.context.filesDir, "offline/attachments/$attachmentId").exists(),
                )
                repo.removeSource(sourceId)
            }
        }
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
        val failSearch = AtomicBoolean(false)
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
                        if (path == "/maniax/api/=/product.json") {
                            val rj = request.url.queryParameter("workno")!!
                            return response
                                .body(
                                    """[{"product_id":"$rj","work_name":"官方示例作品${rj.drop(2).toLong()}","maker_name":"官方示例社团","regist_date":"2026-01-02 00:00:00","series_name":"官方示例系列","genres":[{"id":1,"name":"官方示例标签"}],"creaters":{"scenario_by":[{"name":"官方示例剧本"}],"illust_by":[{"name":"官方示例插画"}],"voice_by":[{"name":"官方示例声优"}]},"image_main":{"url":"${server.url("/cover.png")}"}}]""",
                                )
                                .build()
                        }
                        if (path == "/maniax/product/info/ajax") {
                            val rj = request.url.queryParameter("product_id")!!
                            return response
                                .body(
                                    """{"$rj":{"product_id":"$rj","work_name":"官方示例作品${rj.drop(2).toLong()}","maker_name":"官方示例社团","work_image":"${server.url("/cover.png")}","rate_average_2dp":4.88,"rate_average_star":50,"rate_count":185}}""",
                                )
                                .build()
                        }
                        if (path.startsWith("/maniax/work/"))
                            return response
                                .body(
                                    """<h1 id="work_name">官方示例作品</h1><div class="work_parts_container"><h3 class="work_parts_heading">官方介绍标题</h3><p>官方介绍段落</p><img src="${server.url("/cover.png")}"></div><table id="work_outline"><tr><th>声優</th><td><a>官方示例声优</a></td></tr></table>""",
                                )
                                .build()
                        if (path == "/api/works" || path.startsWith("/api/search/")) {
                            if (path.startsWith("/api/search/")) {
                                searches.incrementAndGet()
                                if (failSearch.get()) return response.code(503).build()
                            }
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
            val metadataClient = DlsiteClient(repo.http, server.url("/"))
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
            ui.onNodeWithTag("asmr-search").performTextInput("RJ00000001")
            ui.onNodeWithContentDescription("提交在线搜索").performClick()
            ui.onNodeWithTag("asmr-results").assertExists()
            assertEquals(0, searches.get())
            ui.onNodeWithTag("asmr-search").performTextReplacement("示例")
            ui.onNodeWithText("热门").performClick()
            waitFor { searches.get() > 0 }
            failSearch.set(true)
            ui.onNodeWithContentDescription("刷新在线作品").performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithText("刷新失败").fetchSemanticsNodes().isNotEmpty() }
            failSearch.set(false)
            ui.onNodeWithText("重试").performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithText("刷新失败").fetchSemanticsNodes().isEmpty() }
            val criteriaModel = main {
                androidx.lifecycle
                    .ViewModelProvider(ui.activity)[app.whisp.ui.AppViewModel::class.java]
            }
            assertEquals("示例", criteriaModel.asmrQuery.value?.text)
            assertEquals("dl_count", criteriaModel.asmrQuery.value?.order)
            ui.onNodeWithContentDescription("筛选作品").performClick()
            ui.onNodeWithTag("asmr-facet-input").performTextInput("示例声优")
            ui.onNodeWithText("应用").performClick()
            waitFor {
                criteriaModel.asmrQuery.value?.facets?.contains(AsmrFacet("va", "示例声优")) == true
            }
            ui.onNodeWithTag("asmr-results").assertExists()
            val previewModel = main {
                androidx.lifecycle
                    .ViewModelProvider(ui.activity)[app.whisp.ui.AppViewModel::class.java]
            }
            val seed = runBlocking { repo.asmrWork(sourceId, 1) }
            main { previewModel.prepareAsmrPreview(sourceId, seed, metadataClient) }
            waitFor { previewModel.asmrPreview.value.preview != null }
            ui.onNodeWithText("示例在线作品1").performClick()
            ui.onNodeWithTag("asmr-preview").assertExists()
            assertTrue(runBlocking { repo.dao.allWorks().isEmpty() })
            val workKey = seed.workId
            val seedDetails = previewModel.asmrPreview.value.preview!!.metadata!!
            main {
                previewModel.loadDlsiteDetails(
                    workKey,
                    seed.rj,
                    "description",
                    seedDetails,
                    client = metadataClient,
                )
                previewModel.loadDlsiteDetails(
                    workKey,
                    seed.rj,
                    "statistics",
                    seedDetails,
                    client = metadataClient,
                )
            }
            waitFor {
                previewModel.dlsiteStates.value[workKey]?.details?.description?.isNotEmpty() ==
                    true &&
                    previewModel.dlsiteStates.value[workKey]?.details?.statistics?.rating == 4.88
            }
            ui.onNodeWithTag("asmr-preview").performScrollToNode(hasText("介绍", substring = false))
            ui.onNodeWithText("介绍").performClick()
            ui.onNodeWithTag("asmr-preview").performScrollToNode(hasText("官方介绍标题"))
            ui.onNodeWithText("官方介绍标题").assertIsDisplayed()
            capture("dlsite-introduction.png")
            ui.onNodeWithTag("asmr-preview").performScrollToNode(hasText("资料", substring = false))
            ui.onNodeWithText("资料").performClick()
            ui.onNodeWithTag("asmr-preview").performScrollToNode(hasText("官方示例剧本"))
            ui.onNodeWithText("官方示例剧本").assertIsDisplayed()
            ui.onNodeWithTag("asmr-preview").performScrollToNode(hasText("4.88"))
            ui.onNodeWithText("4.88").assertIsDisplayed()
            capture("dlsite-information.png")
            assertTrue(runBlocking { repo.dao.allWorks().isEmpty() })
            ui.onNodeWithTag("asmr-preview").performScrollToNode(hasText("加入作品库"))
            ui.onNodeWithText("加入作品库").performClick()
            waitFor {
                runBlocking { repo.readDlsiteDetails(workKey)?.description?.isNotEmpty() == true }
            }
            waitFor { repo.library.value.any { it.work.title == "官方示例作品1" } }
            ui.onNodeWithTag("asmr-preview").performScrollToIndex(0)
            ui.onNodeWithContentDescription("返回检索").performClick()
            ui.waitUntil(10000) {
                ui.onAllNodesWithTag("asmr-search").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithTag("asmr-search").assertTextContains("示例")
            ui.onNodeWithTag("asmr-results").assertExists()
            ui.onNodeWithText("作品", useUnmergedTree = true).performClick()
            ui.onNodeWithContentDescription("官方示例作品1").performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithText("播放作品").fetchSemanticsNodes().isNotEmpty() }
            val workId = stableId("asmr.one|work|1")
            val detail = runBlocking { repo.dao.details(workId)!! }
            assertEquals(2, detail.tracks.size)
            assertEquals("官方示例声优", detail.work.artist)
            assertEquals("官方示例作品1", detail.work.originalTitle)
            assertNotEquals(stableId("$workId|_asmr-cover/main.jpg"), detail.work.coverId)
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
                    repo.importAsmrWork(sourceId, 2, metadataClient)
                    repo.importAsmrWork(sourceId, 1, metadataClient)
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
                    repo.offlineStore.downloadWork(workId)
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
                // Remove a downloaded work while it is playing, using the real UI.
                ui.onNodeWithContentDescription("收起播放器").performClick()
                ui.onNodeWithContentDescription("作品信息").performClick()
                ui.onNodeWithText("删除作品").performClick()
                ui.onNodeWithText("删除").performClick()
                waitFor { runBlocking { repo.dao.work(workId) == null } }
                waitFor { main { controller.mediaItemCount == 0 && !controller.isPlaying } }
                runBlocking {
                    repo.savePlaybackProgress(ProgressEntity(workId, track.id, 1000))
                    repo.flushPlaybackProgress()
                    assertNull(repo.dao.progress(workId))
                    assertNull(repo.dao.dlsiteMetadata(workId))
                    assertTrue(repo.bookmarks.value.none { it.workId == workId })
                    detail.tracks.forEach { assertTrue(repo.dao.locations(it.id).isEmpty()) }
                    assertNotNull(repo.dao.work(stableId("asmr.one|work|2")))
                }
                waitFor { detail.tracks.all { repo.downloads.value[it.id] == null } }
                assertNull(repo.artwork(detail.work.coverId))
            } finally {
                main { MediaController.releaseFuture(future) }
            }
        }
    }
}
