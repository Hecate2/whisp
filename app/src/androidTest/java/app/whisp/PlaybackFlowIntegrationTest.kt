// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
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
class PlaybackFlowIntegrationTest {
    @get:Rule(order = 0)
    val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val repo
        get() = (ui.activity.application as WhispApp).repository

    private var diagnostics: () -> String = { "controller not ready" }

    private fun waitFor(timeout: Long = 30000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Condition timed out: " + diagnostics())
    }

    @Test
    fun backgroundScanPersistentSubtitlesAndPlaybackControls() {
        assertTrue(repo.context.packageName.endsWith(".smoketest"))
        val args = InstrumentationRegistry.getArguments()
        val source = runBlocking {
            repo.addSmb(
                "Flow",
                requireNotNull(args.getString("smbRoot")),
                requireNotNull(args.getString("smbUser")),
                requireNotNull(args.getString("smbPassword")),
            )
        }
        waitFor { source in repo.busy.value }
        waitFor {
            repo.context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.notification.channelId == "library-scan"
            }
        }
        instrumentation.runOnMainSync { ui.activity.moveTaskToBack(true) }
        waitFor(60000) {
            runBlocking { repo.dao.source(source)!!.lastScan > 0 } && source !in repo.busy.value
        }
        val detail = runBlocking { repo.dao.allWorks().single().let { repo.dao.details(it.id)!! } }
        assertEquals(2, detail.tracks.size)
        assertTrue(detail.tracks.all { it.durationMs in 1_850_000..1_854_000 })
        val tracks = detail.tracks.sortedBy { it.order }
        val future =
            MediaController.Builder(
                    repo.context,
                    SessionToken(
                        repo.context,
                        ComponentName(repo.context, PlaybackService::class.java),
                    ),
                )
                .buildAsync()
        val c = future.get(20, TimeUnit.SECONDS)
        lateinit var model: AppViewModel
        instrumentation.runOnMainSync {
            assertTrue(c.isCommandAvailable(Player.COMMAND_SET_VOLUME))
            c.volume = 0f
            model = ViewModelProvider(ui.activity)[AppViewModel::class.java]
            ui.activity.startActivity(
                Intent(ui.activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        diagnostics = {
            var description = ""
            instrumentation.runOnMainSync {
                description =
                    "model=${model.player.value}; state=${c.playbackState}; suppression=${c.playbackSuppressionReason}; play=${c.playWhenReady}; position=${c.currentPosition}; error=${c.playerError?.errorCodeName}"
            }
            description
        }
        try {
            ui.onNodeWithContentDescription("测试作品").performClick()
            ui.onNodeWithText("播放作品").performClick()
            waitFor {
                model.player.value.playWhenReady && model.player.value.trackId == tracks[0].id
            }
            waitFor { model.position.value > 0 }
            val beforeClear = model.position.value
            runBlocking {
                repo.clearTemporaryCache()
                repo.setSettings(streamingCacheMiB = 64)
            }
            waitFor { model.position.value > beforeClear + 500 }
            assertEquals(tracks[0].id, model.player.value.trackId)
            assertTrue(model.player.value.playWhenReady)
            ui.onNodeWithContentDescription("暂停").performClick()
            waitFor { !model.player.value.playWhenReady }
            waitFor { model.player.value.durationMs > 0 }
            instrumentation.runOnMainSync { model.seek(4000) }
            waitFor { model.position.value == 4000L }
            waitFor { model.cues.value.isNotEmpty() }
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()
            ui.onNodeWithText("Track 1 first").assertIsDisplayed()
            val pane = ui.onNodeWithTag("subtitle-pane").fetchSemanticsNode().boundsInRoot
            val lineHeight = ui.onNodeWithTag("subtitle-0").fetchSemanticsNode().boundsInRoot.height
            ui.onNodeWithTag("subtitle-pane").performTouchInput { swipeUp() }
            ui.onNodeWithContentDescription("跟随字幕").assertIsDisplayed()
            val readingBounds = ui.onNodeWithTag("subtitle-1").fetchSemanticsNode().boundsInRoot
            instrumentation.runOnMainSync { model.seek(6000) }
            waitFor { model.position.value == 6000L }
            ui.onNodeWithContentDescription("跟随字幕").assertIsDisplayed()
            assertEquals(
                "Seeking during manual reading must not scroll the script",
                readingBounds,
                ui.onNodeWithTag("subtitle-1").fetchSemanticsNode().boundsInRoot,
            )
            instrumentation.runOnMainSync { model.seek(4000) }
            waitFor { model.position.value == 4000L }
            ui.onNodeWithContentDescription("跟随字幕").performClick()
            ui.onNodeWithContentDescription("播放").assertIsDisplayed()
            ui.onNodeWithTag("subtitle-0").performClick()
            ui.onNodeWithText("从这里播放").assertIsDisplayed()
            assertFalse("Reading a subtitle must not start audio", model.player.value.playWhenReady)
            assertEquals("Reading a subtitle must not seek", 4000L, model.position.value)
            ui.onNodeWithText("从这里播放").performClick()
            waitFor { model.player.value.playWhenReady && model.position.value < 3000 }
            ui.onNodeWithContentDescription("暂停").performClick()
            waitFor { !model.player.value.playWhenReady }
            ui.onNodeWithContentDescription("播放队列").performClick()
            ui.onNodeWithText("播放队列").assertExists()
            ui.onNodeWithText("02").performClick()
            waitFor {
                model.player.value.trackId == tracks[1].id && model.player.value.playWhenReady
            }
            waitFor { model.cues.value.firstOrNull()?.text == "Track 2 first" }
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()
            assertEquals(pane, ui.onNodeWithTag("subtitle-pane").fetchSemanticsNode().boundsInRoot)
            ui.onNodeWithContentDescription("暂停").performClick()
            instrumentation.runOnMainSync {
                model.seek(6000)
                model.playWork(detail, tracks[1].id)
            }
            waitFor { model.player.value.playWhenReady && model.position.value >= 6000 }
            assertTrue(
                "Selecting the current track must preserve its position",
                model.position.value < 10000,
            )
            ui.onNodeWithText("Track 2 second").assertIsDisplayed()
            assertEquals(
                "Changing the active cue must not change the subtitle font or line height",
                lineHeight,
                ui.onNodeWithTag("subtitle-1").fetchSemanticsNode().boundsInRoot.height,
                .5f,
            )
            ui.onNodeWithContentDescription("暂停").performClick()
            instrumentation.runOnMainSync { model.seek(1000) }
            ui.onNodeWithContentDescription("片段").performClick()
            ui.onNodeWithText("设起点").performClick()
            instrumentation.runOnMainSync { model.seek(3000) }
            ui.onNodeWithText("设终点").performClick()
            waitFor { model.player.value.loopStart == 1000L && model.player.value.loopEnd == 3000L }
            ui.onNodeWithText("取消循环").assertIsDisplayed()
            // Close the sheet using the system Back action; the active loop remains.
            androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack()
            ui.onNodeWithContentDescription("更多播放操作").performClick()
            ui.onNodeWithText("播放设置").performClick()
            ui.onNodeWithTag("reduce-motion").performClick()
            waitFor { repo.settings.value.reduceMotion }
            ui.onNodeWithText("完成").performClick()
            runBlocking { repo.setSettings(size = 24, hide = true) }
            waitFor { repo.settings.value.subtitleSize == 24 && repo.settings.value.hideArtwork }
            assertTrue(
                "Other preferences must retain reduced motion",
                repo.settings.value.reduceMotion,
            )
            Thread.sleep(600)
            assertEquals(
                "Preferences must retain an active loop",
                1000L,
                model.player.value.loopStart,
            )
            // Relative notification/headset seeks inside the range must retain the loop.
            instrumentation.runOnMainSync { model.loop(1000, 30000) }
            waitFor { model.player.value.loopEnd == 30000L }
            instrumentation.runOnMainSync { c.seekTo(15000) }
            waitFor { model.position.value == 15000L }
            instrumentation.runOnMainSync { c.seekBack() }
            waitFor { model.position.value == 5000L }
            assertEquals(1000L, model.player.value.loopStart)
            // An out-of-range seek cancels it regardless of the control surface.
            instrumentation.runOnMainSync { c.seekTo(40000) }
            waitFor { model.player.value.loopStart == -1L }
            waitFor { model.position.value == 40000L }
            ui.onNodeWithContentDescription("更多播放操作").performClick()
            ui.onNodeWithText("精确跳转").performClick()
            ui.onNodeWithText("+1 秒").performClick()
            ui.onNodeWithText("跳转").performClick()
            waitFor { model.position.value == 41000L }
            assertFalse("Precise seeking must retain a pause", model.player.value.playWhenReady)
            ui.onNodeWithTag("seek-bar").performTouchInput {
                down(center)
                moveBy(androidx.compose.ui.geometry.Offset(20f, 0f))
                moveBy(androidx.compose.ui.geometry.Offset(0f, -160f))
                moveBy(androidx.compose.ui.geometry.Offset(15f, 0f))
                up()
            }
            assertFalse(model.player.value.playWhenReady)
            // A completed queue stops. Both external controls and the app restart its first item.
            instrumentation.runOnMainSync {
                c.seekTo(1, tracks[1].durationMs - 200)
                c.play()
            }
            waitFor { model.player.value.ended }
            assertFalse(model.player.value.playWhenReady)
            instrumentation.runOnMainSync { c.play() }
            waitFor {
                model.player.value.trackId == tracks[0].id && model.player.value.playWhenReady
            }
            instrumentation.runOnMainSync {
                c.seekTo(1, tracks[1].durationMs - 200)
                c.play()
            }
            waitFor { model.player.value.ended }
            ui.onNodeWithContentDescription("重新播放作品").performClick()
            waitFor {
                model.player.value.trackId == tracks[0].id && model.player.value.playWhenReady
            }
            ui.onNodeWithContentDescription("暂停").performClick()
            waitFor { !model.player.value.playWhenReady }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(repo.context.getExternalFilesDir(null), "ux-player.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            instrumentation.runOnMainSync { ui.activity.moveTaskToBack(true) }
            Thread.sleep(1200)
            assertFalse(model.player.value.playWhenReady)
            instrumentation.runOnMainSync {
                ui.activity.startActivity(
                    Intent(ui.activity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()
            instrumentation.runOnMainSync {
                model.playWork(detail, tracks[1].id, startPosition = 5000)
                model.togglePlay()
            }
            waitFor {
                model.openingTrackId.value == null && model.player.value.trackId == tracks[1].id
            }
            Thread.sleep(1500)
            assertFalse(
                "Pausing while opening must retain the target and stay paused after buffering",
                model.player.value.playWhenReady,
            )
            assertTrue(model.position.value >= 5000)
            waitFor { repo.progress.value.firstOrNull()?.trackId == tracks[1].id }
            instrumentation.runOnMainSync { c.clearMediaItems() }
            waitFor { model.player.value.trackId == null }
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()
            ui.onNodeWithText("Track 2 second").assertIsDisplayed()
            assertFalse(
                "Opening the remembered player must not autoplay",
                model.player.value.playWhenReady,
            )
            // Return through the same cover without changing playback or the remembered cue.
            ui.onNodeWithContentDescription("收起播放器").performClick()
            ui.onNodeWithContentDescription("返回作品库").performClick()
            ui.onNodeWithContentDescription("测试作品").assertIsDisplayed()
            ui.onNodeWithContentDescription("测试作品").performClick()
            assertFalse("Navigating to a work must not autoplay", model.player.value.playWhenReady)
            runBlocking { repo.setSettings(reduceMotion = false) }
            waitFor { !repo.settings.value.reduceMotion }
            ui.onNodeWithContentDescription("返回作品库").performClick()
            ui.onNodeWithContentDescription("测试作品").assertIsDisplayed()
            ui.onNodeWithTag("mini-player").performClick()
            // Back can interrupt expansion without leaving an extra player in the stack.
            androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack()
            ui.onNodeWithContentDescription("测试作品").assertIsDisplayed()
            ui.onNodeWithTag("mini-player").performClick()
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()
            ui.onNodeWithText("Track 2 second").assertIsDisplayed()
            assertFalse(
                "Mini-player expansion must preserve paused playback",
                model.player.value.playWhenReady,
            )
        } finally {
            instrumentation.runOnMainSync {
                c.pause()
                MediaController.releaseFuture(future)
            }
        }
    }
}
