// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.*
import app.whisp.playback.PlaybackService
import app.whisp.ui.AppViewModel
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerStateUiIntegrationTest {
    @get:Rule(order = 0)
    val permission =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun subtitleRetryAndQueueRemainCorrectWhileLibraryChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repo = (ui.activity.application as WhispApp).repository
        assertTrue(repo.context.packageName.endsWith(".smoketest"))
        val wave = SystemMediaIntegrationTest.silentWave(repo.context.filesDir)
        val caption = File(repo.context.filesDir, "player-ui-probe.vtt").apply { delete() }
        val work = WorkEntity("player-ui-probe", null, "播放器示例", "播放器示例")
        val source =
            SourceEntity("player-ui-source", "示例来源", "local", wave.parentFile!!.toURI().toString())
        val tracks =
            listOf(
                TrackEntity(
                    "player-ui-a",
                    work.id,
                    "示例曲目 A",
                    "a.wav",
                    "main/a.wav",
                    1,
                    40000,
                    "player-ui-caption",
                ),
                TrackEntity("player-ui-b", work.id, "示例曲目 B", "b.wav", "main/b.wav", 2, 40000),
            )
        val extra = TrackEntity("player-ui-c", work.id, "新扫描曲目 C", "c.wav", "main/c.wav", 3, 40000)
        runBlocking {
            repo.dao.putWorks(listOf(work))
            repo.dao.putSource(source)
            repo.dao.putTracks(tracks)
            repo.dao.putAttachments(
                listOf(
                    AttachmentEntity(
                        "player-ui-caption",
                        work.id,
                        "示例字幕",
                        caption.name,
                        caption.name,
                        "subtitle",
                    ),
                ),
            )
            repo.dao.putLocations(
                tracks.map {
                    LocationEntity(
                        "location-${it.id}",
                        it.id,
                        source.id,
                        wave.toURI().toString(),
                        wave.length(),
                    )
                } +
                    LocationEntity(
                        "location-caption",
                        "player-ui-caption",
                        source.id,
                        caption.toURI().toString(),
                        0,
                    ),
            )
        }
        ui.waitUntil(5000) { repo.library.value.any { it.work.id == work.id } }
        val future =
            MediaController.Builder(
                    repo.context,
                    SessionToken(
                        repo.context,
                        ComponentName(repo.context, PlaybackService::class.java),
                    ),
                )
                .buildAsync()
        val controller = future.get(20, TimeUnit.SECONDS)
        lateinit var model: AppViewModel
        instrumentation.runOnMainSync {
            controller.volume = 0f
            model = ViewModelProvider(ui.activity)[AppViewModel::class.java]
        }
        try {
            ui.onNodeWithContentDescription(work.title).performClick()
            ui.onNodeWithText("播放作品").performClick()
            ui.waitUntil(15_000) {
                model.player.value.trackId == tracks.first().id &&
                    model.subtitleError.value != null &&
                    model.queueTracks.value.map { it.id } == tracks.map { it.id }
            }
            ui.onNodeWithText("重试字幕").assertIsDisplayed()
            ui.onNodeWithText("暂无字幕").assertDoesNotExist()
            ui.onNodeWithText("字幕暂时不可用").assertDoesNotExist()
            caption.writeText("WEBVTT\n\n00:00:00.000 --> 00:01:00.000\n恢复后的示例字幕\n")
            ui.onNodeWithText("重试字幕").performClick()
            ui.waitUntil(5000) { model.cues.value.firstOrNull()?.text == "恢复后的示例字幕" }
            assertNull(model.subtitleError.value)
            ui.onNodeWithText("恢复后的示例字幕").assertIsDisplayed()
            ui.onNodeWithContentDescription("播放队列").performClick()
            ui.waitUntil(5000) { ui.onNodeWithText("示例曲目 B").isDisplayed() }
            runBlocking {
                repo.dao.deleteStaleTracks(work.id, listOf(tracks.first().id))
                repo.dao.putTracks(listOf(extra))
                repo.dao.putLocations(
                    listOf(
                        LocationEntity(
                            "location-${extra.id}",
                            extra.id,
                            source.id,
                            wave.toURI().toString(),
                            wave.length(),
                        ),
                    ),
                )
            }
            ui.waitUntil(5000) {
                repo.library.value.first { it.work.id == work.id }.tracks.map { it.id }.toSet() ==
                    setOf(tracks.first().id, extra.id)
            }
            ui.onNodeWithText("示例曲目 B").assertIsDisplayed()
            ui.onNodeWithText("新扫描曲目 C").assertDoesNotExist()
            ui.onNodeWithText("示例曲目 B").performClick()
            ui.waitUntil(5000) { model.player.value.trackId == tracks.last().id }
            ui.waitUntil(5000) {
                ui.onAllNodesWithText("播放队列").fetchSemanticsNodes().isEmpty()
            }
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()
            ui.onNodeWithText("02 · 示例曲目 B").assertIsDisplayed()
            instrumentation.runOnMainSync {
                assertEquals(2, controller.mediaItemCount)
                assertEquals(tracks.last().id, controller.currentMediaItem?.mediaId)
            }
        } catch (e: Throwable) {
            val folder = File(repo.context.getExternalFilesDir(null), "player-state-ui-diagnostics")
            folder.mkdirs()
            File(folder, "semantics.txt")
                .writeText(
                    ui.onAllNodes(isRoot(), useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .joinToString("\n\n") { node -> dump(node) } +
                        "\nmodel=" +
                        model.player.value +
                        "\nqueue=" +
                        model.queueTracks.value,
                )
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(folder, "screen.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            throw e
        } finally {
            instrumentation.runOnMainSync {
                controller.pause()
                controller.clearMediaItems()
                MediaController.releaseFuture(future)
            }
            runBlocking {
                repo.dao.deleteSourceLocations(source.id)
                repo.dao.deleteSource(source.id)
                repo.dao.deleteWorkAttachments(work.id)
                repo.dao.deleteWorkTracks(work.id)
                repo.dao.deleteWorkProgress(work.id)
                repo.dao.deleteWork(work.id)
            }
            caption.delete()
            wave.delete()
        }
    }

    private fun dump(
        node: androidx.compose.ui.semantics.SemanticsNode,
        indent: String = "",
    ): String =
        "$indent${node.id}: ${node.boundsInWindow} ${node.config}\n" +
            node.children.joinToString("\n") { dump(it, "$indent  ") }
}
