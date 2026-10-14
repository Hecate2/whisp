// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.content.ComponentName
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.whisp.data.*
import app.whisp.playback.PlaybackService
import app.whisp.ui.AppViewModel
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class VideoPlaybackIntegrationTest {
    @get:Rule(order = 0)
    val permission =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    @get:Rule(order = 2) val testName = org.junit.rules.TestName()
    private val seedId = "video-example-" + java.util.UUID.randomUUID().toString()
    private val repo
        get() = (instrumentation.targetContext.applicationContext as WhispApp).repository

    private fun waitFor(timeoutMs: Long = 10000, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < end) {
            device.findObject(By.res("android:id/ok").text("Got it"))?.click()
            if (condition()) return
            Thread.sleep(50)
        }
        diagnose()
        fail("Video condition timed out")
    }

    private fun clickText(text: String) {
        device.waitForIdle(1000)
        val node = device.wait(Until.findObject(By.text(text)), 10000)
        assertNotNull("Text is visible: $text", node)
        node.click()
    }

    private fun clickDescription(text: String) {
        device.waitForIdle(1000)
        val node = device.wait(Until.findObject(By.desc(text)), 10000)
        if (node == null) diagnose()
        assertNotNull("Control is visible: $text", node)
        node.click()
    }

    private fun visibleText(text: String) = device.hasObject(By.text(text))

    private fun visibleDescription(text: String) = device.hasObject(By.desc(text))

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun seed(portrait: Boolean = false): WorkDetails {
        assertTrue(repo.context.packageName.endsWith(".smoketest"))
        val folder = File(repo.context.filesDir, "video-fixture").apply { mkdirs() }
        val video = File(folder, "01_画面.mp4")
        instrumentation.context.assets
            .open(if (portrait) "video-portrait-example.mp4" else "video-example.mp4")
            .use { input ->
                video.outputStream().use { output -> input.copyTo(output) }
            }
        val nextVideo = File(folder, "02_画面续篇.mp4").also { video.copyTo(it, overwrite = true) }
        val caption =
            File(folder, "01_画面.mp4.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:40,000\n视频示例字幕\n")
            }
        val wave = SystemMediaIntegrationTest.silentWave(folder)
        val work = WorkEntity(seedId, null, "视频示例 ${seedId.takeLast(6)}", "视频示例")
        val source = SourceEntity(seedId + "-source", "视频来源", "local", folder.toURI().toString())
        val indexed =
            LibraryIndexer.index(
                listOf(video, nextVideo, caption, wave).map { file ->
                    val name = if (file == wave) "03_声音.wav" else file.name
                    FileEntry("视频示例/$name", file.toURI().toString(), false, file.length())
                },
                source.id,
                1,
                work,
            )
        runBlocking {
            repo.dao.putSource(source)
            repo.dao.putWorks(indexed.works)
            repo.dao.putTracks(indexed.tracks)
            repo.dao.putAttachments(indexed.attachments)
            repo.dao.putLocations(indexed.locations)
        }
        waitFor(5000) { repo.library.value.any { it.work.id == work.id } }
        return runBlocking { repo.dao.details(work.id)!! }
    }

    private fun diagnose() {
        val folder =
            File(repo.context.getExternalFilesDir(null), "video-diagnostics/${testName.methodName}")
                .apply { mkdirs() }
        device.dumpWindowHierarchy(File(folder, "ui.xml"))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "screen.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        ui.scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[AppViewModel::class.java]
            val p = model.videoPlayer.value
            File(folder, "state.txt")
                .writeText(
                    "${model.player.value}\naudioOnly=${model.audioOnly.value}\nselected=${p?.currentTracks?.isTypeSelected(C.TRACK_TYPE_VIDEO)}\ndisabled=${p?.trackSelectionParameters?.disabledTrackTypes}",
                )
        }
    }

    private fun assertFrame() {
        waitFor(5000) {
            val bounds = device.findObject(By.desc("视频画面"))?.visibleBounds ?: return@waitFor false
            val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return@waitFor false
            try {
                val pixel = screenshot.getPixel(bounds.centerX(), bounds.centerY())
                val folder =
                    File(repo.context.getExternalFilesDir(null), "video-diagnostics").apply {
                        mkdirs()
                    }
                File(folder, "frame.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                android.graphics.Color.red(pixel) > 150 &&
                    android.graphics.Color.green(pixel) > 90 &&
                    android.graphics.Color.blue(pixel) > 130
            } finally {
                screenshot.recycle()
            }
        }
    }

    @Test
    fun nfsVideoScanSeekAndOfflineRenderFrames() {
        assertTrue(repo.context.packageName.endsWith(".smoketest"))
        val root = InstrumentationRegistry.getArguments().getString("nfsRoot")
        assumeTrue("Provide a dedicated synthetic NFS video fixture", root != null)
        val source =
            SourceEntity(
                "nfs-video-example",
                "NAS 视频示例",
                "nfs",
                NfsAddress.parse(root!!, 42).normalized,
            )
        val detail = runBlocking {
            repo.dao.putSource(source)
            repo.scan(source.id)
            repo.dao.details(repo.dao.sourceWorkIds(source.id).single())!!
        }
        assertEquals(1, detail.tracks.size)
        val video = detail.tracks.single()
        assertTrue(video.isVideo)
        assertNotNull(video.subtitleId)
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
        try {
            main { controller.volume = 0f }
            waitFor(5000) { repo.library.value.any { it.work.id == detail.work.id } }
            clickDescription(detail.work.title)
            clickText("播放作品")
            waitFor(15000) { main { controller.isPlaying && controller.videoSize.width > 0 } }
            waitFor { visibleText("视频示例字幕") }
            assertFrame()
            main {
                controller.pause()
                controller.seekTo(20000)
            }
            waitFor(10000) {
                main {
                    controller.currentPosition >= 20000 &&
                        controller.playbackState == Player.STATE_READY
                }
            }
            assertFrame()
            runBlocking { repo.offlineStore.downloadWork(detail.work.id) }
            waitFor(15000) {
                repo.downloads.value[video.id]?.state == Download.STATE_COMPLETED
            }
            waitFor(15000) {
                repo.library.value.first { it.work.id == detail.work.id }.work.offlineAttachments
            }
            main { controller.clearMediaItems() }
            runBlocking { repo.removeSource(source.id) }
            val saved = runBlocking { repo.dao.details(detail.work.id)!! }
            assertTrue(repo.offlineStore.isOffline(saved))
            val item = runBlocking { repo.mediaItem(video, saved.work) }
            main {
                controller.setMediaItem(item)
                controller.prepare()
                controller.play()
            }
            waitFor(10000) { main { controller.isPlaying && controller.videoSize.width > 0 } }
            assertFrame()
        } finally {
            main {
                controller.pause()
                controller.clearMediaItems()
                MediaController.releaseFuture(future)
            }
            runBlocking {
                repo.removeSource(source.id)
                repo.offlineStore.removeOffline(detail.work.id)
            }
        }
    }

    @Test
    fun frameSeekAudioOnlyFullscreenAndOfflineKeepTheSamePlayback() {
        val detail = seed()
        val video = detail.tracks.first { it.isVideo }
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
        try {
            main { controller.volume = 0f }
            clickDescription(detail.work.title)
            clickText("播放作品")
            waitFor(15000) {
                main {
                    controller.isPlaying &&
                        controller.videoSize.width > 0 &&
                        controller.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                }
            }
            waitFor { visibleText("视频示例字幕") }
            assertFrame()
            clickDescription("暂停")
            main { controller.seekTo(15000) }
            waitFor(10000) {
                main {
                    controller.playbackState == Player.STATE_READY &&
                        controller.currentPosition >= 15000
                }
            }
            clickDescription("只听声音")
            waitFor(5000) {
                main { !controller.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO) }
            }
            waitFor { !visibleDescription("视频画面") }
            assertTrue(main { controller.currentPosition >= 15000 })
            clickDescription("显示画面")
            waitFor(10000) {
                main { controller.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO) }
            }
            assertTrue(main { controller.currentPosition >= 15000 })
            assertFrame()
            clickDescription("全屏")
            waitFor(10000) { visibleDescription("退出全屏") }
            waitFor { visibleText("视频示例字幕") }
            assertFrame()
            val next = detail.tracks.last { it.isVideo }
            main {
                controller.play()
                controller.seekToNextMediaItem()
            }
            waitFor {
                main {
                    controller.currentMediaItem?.mediaId == next.id &&
                        controller.playbackState == Player.STATE_READY
                }
            }
            waitFor { visibleDescription("退出全屏") }
            assertFrame()
            assertFalse(visibleText("视频示例字幕"))
            ui.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(10000) { visibleDescription("全屏") }
            assertEquals(next.id, main { controller.currentMediaItem?.mediaId })
            main { controller.seekTo(0, 15000) }
            waitFor {
                main {
                    controller.currentMediaItem?.mediaId == video.id &&
                        controller.currentPosition >= 15000 &&
                        controller.playbackState == Player.STATE_READY
                }
            }
            assertTrue(main { controller.currentPosition >= 15000 })
            runBlocking { repo.offlineStore.downloadWork(detail.work.id) }
            waitFor(15000) {
                detail.tracks.all { repo.downloads.value[it.id]?.state == Download.STATE_COMPLETED }
            }
            waitFor(15000) {
                repo.library.value.first { it.work.id == detail.work.id }.work.offlineAttachments
            }
            main { controller.clearMediaItems() }
            runBlocking { repo.removeSource(seedId + "-source") }
            File(repo.context.filesDir, "video-fixture").deleteRecursively()
            val saved = runBlocking { repo.dao.details(detail.work.id)!! }
            assertTrue(repo.offlineStore.isOffline(saved))
            val item = runBlocking { repo.mediaItem(video, saved.work) }
            main {
                controller.setMediaItem(item)
                controller.prepare()
                controller.play()
            }
            waitFor(10000) { main { controller.isPlaying && controller.videoSize.width > 0 } }
            waitFor { visibleText("视频示例字幕") }
            assertFrame()
        } finally {
            main {
                controller.pause()
                controller.clearMediaItems()
                MediaController.releaseFuture(future)
            }
            runBlocking {
                repo.removeSource(seedId + "-source")
                repo.offlineStore.removeOffline(detail.work.id)
            }
        }
    }

    @Test
    fun backgroundAndAudioModeSurviveActivityRecreationWithoutRestarting() {
        val detail = seed(portrait = true)
        val video = detail.tracks.first { it.isVideo }
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
        try {
            main {
                controller.volume = 0f
                ui.scenario.onActivity { model = ViewModelProvider(it)[AppViewModel::class.java] }
            }
            clickDescription(detail.work.title)
            clickText("播放作品")
            waitFor(15000) {
                main {
                    controller.isPlaying &&
                        controller.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                }
            }
            waitFor { model.player.value.videoAspectRatio < 1f }
            clickDescription("全屏")
            waitFor { visibleDescription("退出全屏") }
            assertTrue(device.displayHeight > device.displayWidth)
            val before = main { controller.currentPosition }
            ui.scenario.moveToState(Lifecycle.State.CREATED)
            waitFor(5000) {
                main {
                    controller.trackSelectionParameters.disabledTrackTypes.contains(
                        C.TRACK_TYPE_VIDEO,
                    )
                }
            }
            assertTrue(main { controller.isPlaying && controller.currentPosition >= before })
            ui.scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor(10000) {
                main { controller.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO) }
            }
            assertTrue(model.player.value.videoAspectRatio < 1f)
            assertTrue(device.displayHeight > device.displayWidth)
            if (!visibleDescription("退出全屏")) clickDescription("视频画面")
            clickDescription("退出全屏")
            clickDescription("只听声音")
            ui.scenario.recreate()
            waitFor { visibleDescription("显示画面") }
            waitFor { !visibleDescription("视频画面") }
            assertTrue(model.audioOnly.value)
            assertEquals(video.id, main { controller.currentMediaItem?.mediaId })
            assertTrue(main { controller.isPlaying && controller.currentPosition >= before })
            assertTrue(
                main {
                    controller.trackSelectionParameters.disabledTrackTypes.contains(
                        C.TRACK_TYPE_VIDEO,
                    )
                },
            )
            clickDescription("播放队列")
            clickText("声音")
            waitFor(5000) {
                main {
                    controller.currentMediaItem?.mediaId == detail.tracks.first { !it.isVideo }.id
                }
            }
            waitFor { !visibleDescription("视频画面") }
            waitFor { !visibleDescription("显示画面") }
        } finally {
            main {
                model.setAudioOnly(false)
                controller.pause()
                controller.clearMediaItems()
                MediaController.releaseFuture(future)
            }
            runBlocking { repo.removeSource(seedId + "-source") }
        }
    }
}
