// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.content.ComponentName
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.*
import androidx.room.InvalidationTracker
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.whisp.data.*
import app.whisp.playback.PlaybackService
import app.whisp.ui.AppViewModel
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PerformanceIntegrationTest {
    @get:Rule(order = 0)
    val permission =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val repo
        get() = (ui.activity.application as WhispApp).repository

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitFor(message: String, timeout: Long = 15000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail(message)
    }

    private fun seed(): WorkDetails {
        assertTrue(repo.context.packageName.endsWith(".smoketest"))
        runBlocking { withContext(Dispatchers.IO) { repo.database.clearAllTables() } }
        val wave = SystemMediaIntegrationTest.silentWave(repo.context.filesDir)
        val vtt =
            File(repo.context.filesDir, "performance.vtt").apply {
                writeText(
                    "WEBVTT\n\n00:00.000 --> 00:02.000\nFirst\n\n00:02.000 --> 00:06.000\nSecond\n\n00:06.000 --> 00:39.000\nThird\n",
                )
            }
        runBlocking {
            repo.setSettings(hide = false, reduceMotion = true)
            repo.dao.putSource(
                SourceEntity(
                    "performance-source",
                    "Performance",
                    "local",
                    wave.parentFile!!.toURI().toString(),
                ),
            )
            repo.dao.putWorks(
                listOf(
                    WorkEntity("performance-work", null, "Performance work", "Performance work"),
                ),
            )
            repo.dao.putTracks(
                listOf(
                    TrackEntity(
                        "performance-track",
                        "performance-work",
                        "Silent track",
                        "1.wav",
                        "audio/1.wav",
                        1,
                        40000,
                        "performance-subtitle",
                    ),
                ),
            )
            repo.dao.putAttachments(
                listOf(
                    AttachmentEntity(
                        "performance-subtitle",
                        "performance-work",
                        "Subtitles",
                        "performance.vtt",
                        "performance.vtt",
                        "subtitle",
                    ),
                ),
            )
            repo.dao.putLocations(
                listOf(
                    LocationEntity(
                        "performance-audio",
                        "performance-track",
                        "performance-source",
                        wave.toURI().toString(),
                        wave.length(),
                    ),
                    LocationEntity(
                        "performance-text",
                        "performance-subtitle",
                        "performance-source",
                        vtt.toURI().toString(),
                        vtt.length(),
                    ),
                ),
            )
        }
        waitFor("Library did not load") {
            repo.library.value.any { it.work.id == "performance-work" }
        }
        waitFor("Previous progress must clear") {
            repo.progress.value.none { it.workId == "performance-work" }
        }
        return runBlocking { repo.dao.details("performance-work")!! }
    }

    private fun controller(): MediaController = main {
        MediaController.Builder(
                repo.context,
                SessionToken(
                    repo.context,
                    ComponentName(repo.context, PlaybackService::class.java),
                ),
            )
            .buildAsync()
    }
        .get(20, TimeUnit.SECONDS)

    private fun foreground() {
        main {
            ui.activity
                .getSystemService(android.app.ActivityManager::class.java)
                .appTasks
                .first { it.taskInfo?.taskId == ui.activity.taskId }
                .moveToFront()
        }
        assertTrue(
            "Test activity must become visible",
            UiDevice.getInstance(instrumentation)
                .wait(Until.hasObject(By.pkg(repo.context.packageName)), 10000),
        )
        ui.waitForIdle()
    }

    @Test
    fun timelineStopsOutsidePlayerWhileAudioContinuesAndRefreshesOnReturn() {
        val detail = seed()
        val c = controller()
        val model = main { ViewModelProvider(ui.activity)[AppViewModel::class.java] }
        try {
            main { c.volume = 0f }
            foreground()
            ui.onNodeWithContentDescription("Performance work").performClick()
            ui.onNodeWithText("播放作品").performClick()
            waitFor("Playback must start") { main { c.isPlaying } }
            // The page's LaunchedEffect uses Compose's virtual test clock. Advance
            // it explicitly while the real audio clock continues on the device.
            fun waitTimeline(message: String, condition: () -> Boolean) =
                waitFor(message) {
                    ui.mainClock.advanceTimeBy(200)
                    condition()
                }
            waitTimeline("Visible timeline must advance") { model.position.value > 800 }
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()
            Thread.sleep(300)
            val event = model.player.value
            val position = model.position.value
            Thread.sleep(700)
            ui.mainClock.advanceTimeBy(700)
            assertTrue(model.position.value > position)
            assertSame(
                "Time must not emit a new page-level playback snapshot",
                event,
                model.player.value,
            )

            ui.onNodeWithContentDescription("收起播放器").performClick()
            ui.onNodeWithContentDescription("返回作品库").performClick()
            ui.onNodeWithTag("library-grid").assertIsDisplayed()
            Thread.sleep(300)
            val libraryPosition = model.position.value
            val audioPosition = main { c.currentPosition }
            Thread.sleep(1000)
            ui.mainClock.advanceTimeBy(1000)
            assertEquals(
                "Library must have no timeline clock",
                libraryPosition,
                model.position.value,
            )
            assertTrue(
                "Audio must continue",
                main { c.isPlaying && c.currentPosition > audioPosition + 500 },
            )
            ui.onNodeWithTag("mini-player").performClick()
            waitTimeline("Reopening player must refresh time") {
                model.position.value > libraryPosition + 500
            }
            ui.onNodeWithTag("subtitle-pane").performTouchInput { swipeUp() }
            ui.onNodeWithContentDescription("跟随字幕").assertIsDisplayed()

            UiDevice.getInstance(instrumentation).pressHome()
            Thread.sleep(300)
            val backgroundPosition = model.position.value
            Thread.sleep(1000)
            ui.mainClock.advanceTimeBy(1000)
            assertEquals(
                "Background UI must have no clock",
                backgroundPosition,
                model.position.value,
            )
            assertTrue(main { c.isPlaying })
            foreground()
            waitTimeline("Foreground timeline must catch up") {
                model.position.value > backgroundPosition + 500
            }
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()
            ui.onNodeWithContentDescription("跟随字幕").assertIsDisplayed()
            ui.onNodeWithContentDescription("跟随字幕").performClick()
            main { model.togglePlay() }
            waitFor("Pause must be immediate") { !model.player.value.playWhenReady }
            main { model.seek(4000) }
            waitFor("Paused seek must update without polling") { model.position.value == 4000L }
            ui.onNodeWithText("Second").assertIsDisplayed()
            Thread.sleep(800)
            ui.mainClock.advanceTimeBy(1000)
            assertEquals(4000L, model.position.value)
            runBlocking { repo.flushPlaybackProgress() }
            assertEquals(4000L, runBlocking { repo.dao.progress(detail.work.id)!!.positionMs })
        } finally {
            main {
                c.pause()
                c.clearMediaItems()
                c.release()
            }
            runBlocking { repo.flushPlaybackProgress() }
        }
    }

    @Test
    fun shortBackgroundLoopsAvoidRepeatedWritesAndTimerStillStopsPlayback() {
        val detail = seed()
        val c = controller()
        val wraps = AtomicInteger()
        try {
            main {
                c.volume = 0f
                c.addListener(
                    object : Player.Listener {
                        override fun onPositionDiscontinuity(
                            old: Player.PositionInfo,
                            new: Player.PositionInfo,
                            reason: Int,
                        ) {
                            if (new.positionMs == 1000L && old.positionMs >= 2000L)
                                wraps.incrementAndGet()
                        }
                    },
                )
            }
            val item = runBlocking { repo.mediaItem(detail.tracks.single(), detail.work) }
            main {
                c.setMediaItem(item, 1000)
                c.prepare()
            }
            waitFor("Audio must prepare") { main { c.playbackState == Player.STATE_READY } }
            main {
                    c.sendCustomCommand(
                        SessionCommand(PlaybackService.LOOP, Bundle.EMPTY),
                        Bundle().apply {
                            putLong("start", 1000)
                            putLong("end", 2000)
                        },
                    )
                }
                .get(10, TimeUnit.SECONDS)
            main {
                    c.sendCustomCommand(
                        SessionCommand(PlaybackService.TIMER, Bundle.EMPTY),
                        Bundle().apply { putInt("minutes", 1) },
                    )
                }
                .get(10, TimeUnit.SECONDS)
            val deadline = main { c.sessionExtras.getLong("timerEnd") }
            assertTrue(deadline > android.os.SystemClock.elapsedRealtime())
            main { c.play() }
            UiDevice.getInstance(instrumentation).pressHome()
            waitFor("Background audio must play") { main { c.isPlaying } }
            Thread.sleep(600)
            runBlocking { repo.flushPlaybackProgress() }
            val saved = runBlocking { repo.dao.progress(detail.work.id)!! }
            waitFor("Loop must wrap at least four times", 10000) { wraps.get() >= 4 }
            assertEquals(
                "Automatic loop seeks must not write progress each time",
                saved,
                runBlocking { repo.dao.progress(detail.work.id)!! },
            )
            assertTrue(
                "Loop boundary must stay within the prior 40 ms tolerance plus device scheduling",
                main { c.currentPosition in 1000..2200 },
            )
            waitFor("15-second checkpoint must still save progress", 20000) {
                runBlocking { repo.dao.progress(detail.work.id)!!.updatedAt > saved.updatedAt }
            }
            waitFor("One-minute background timer must stop playback", 55000) {
                main { c.sessionExtras.getLong("timerEnd") == 0L && !c.playWhenReady }
            }
            assertTrue(
                "Timer must not stop early",
                android.os.SystemClock.elapsedRealtime() >= deadline,
            )
            assertTrue(
                "Timer should fire promptly",
                android.os.SystemClock.elapsedRealtime() - deadline < 2500,
            )
            runBlocking { repo.flushPlaybackProgress() }
            assertTrue(runBlocking { repo.dao.progress(detail.work.id)!!.positionMs in 1000..2200 })
        } finally {
            main {
                c.pause()
                c.clearMediaItems()
                c.release()
            }
            runBlocking { repo.flushPlaybackProgress() }
            foreground()
        }
    }

    @Test
    fun activeDownloadReportsBytesThenRetainsCompletionAndRemoval() {
        val detail = seed()
        val bytes = SystemMediaIntegrationTest.silentWave(repo.context.filesDir).readBytes()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val snapshots = Collections.synchronizedList(mutableListOf<Pair<Int, Long>>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // A local, throttled HTTP fixture avoids test-only dependencies on R8-removed
        // OkHttp internals. Zero PCM samples remain silent if accidentally played.
        val serving = Thread {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) {}
                    val output = socket.getOutputStream()
                    output.write(
                        ("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n" +
                                "Content-Type: audio/wav\r\nConnection: close\r\n\r\n")
                            .toByteArray(),
                    )
                    for (offset in bytes.indices step 32768) {
                        output.write(bytes, offset, minOf(32768, bytes.size - offset))
                        output.flush()
                        Thread.sleep(100)
                    }
                }
            } catch (_: Exception) {
                /* Closing the fixture also cancels a failed test. */
            }
        }
            .apply {
                isDaemon = true
                start()
            }
        runBlocking {
            repo.dao.putLocations(
                listOf(
                    LocationEntity(
                        "performance-audio",
                        "performance-track",
                        "performance-source",
                        "http://127.0.0.1:${server.localPort}/silent.wav",
                        bytes.size.toLong(),
                    ),
                ),
            )
        }
        val observer = scope.launch {
            repo.downloads.collect { map ->
                map["performance-track"]?.let { snapshots += it.state to it.bytesDownloaded }
            }
        }
        try {
            // Exercise the repository's observer pipeline headlessly, independently
            // of the user's keyguard or foreground-service launch restrictions.
            main {
                repo.downloadManager.resumeDownloads()
                repo.downloadManager.addDownload(
                    androidx.media3.exoplayer.offline.DownloadRequest.Builder(
                            "performance-track",
                            android.net.Uri.parse(
                                "http://127.0.0.1:${server.localPort}/silent.wav",
                            ),
                        )
                        .setCustomCacheKey("performance-track")
                        .build(),
                )
            }
            waitFor("Download must report live byte progress", 20000) {
                synchronized(snapshots) {
                    snapshots.any {
                        it.first == Download.STATE_DOWNLOADING &&
                            it.second > 0 &&
                            it.second < bytes.size
                    }
                }
            }
            waitFor("Download completion must be delivered", 20000) {
                repo.downloads.value["performance-track"]?.state == Download.STATE_COMPLETED
            }
            assertEquals(
                bytes.size.toLong(),
                repo.downloads.value["performance-track"]!!.bytesDownloaded,
            )
            Thread.sleep(500)
            val count = snapshots.size
            Thread.sleep(1800)
            assertEquals(
                "Completed downloads must not emit idle progress ticks",
                count,
                snapshots.size,
            )
            main { repo.downloadManager.removeDownload("performance-track") }
            waitFor("Removal must be delivered") { "performance-track" !in repo.downloads.value }
            Thread.sleep(1800)
            assertFalse(
                "An old sample must not restore a removed download",
                repo.downloads.value.containsKey("performance-track"),
            )
        } finally {
            main {
                repo.downloadManager.removeDownload("performance-track")
                repo.downloadManager.pauseDownloads()
            }
            observer.cancel()
            scope.cancel()
            server.close()
            serving.interrupt()
            serving.join(2000)
        }
    }

    @Test
    fun unchangedDurationDoesNotInvalidateTheLibrary() {
        seed()
        val invalidations = AtomicInteger()
        val observer =
            object : InvalidationTracker.Observer("tracks") {
                override fun onInvalidated(tables: Set<String>) {
                    invalidations.incrementAndGet()
                }
            }
        Thread.sleep(300)
        repo.database.invalidationTracker.addObserver(observer)
        try {
            runBlocking { repo.dao.updateDuration("performance-track", 40000) }
            Thread.sleep(400)
            assertEquals(
                "Identical duration must not trigger library queries",
                0,
                invalidations.get(),
            )
            runBlocking { repo.dao.updateDuration("performance-track", 41000) }
            waitFor("Changed duration must invalidate the library") { invalidations.get() > 0 }
        } finally {
            repo.database.invalidationTracker.removeObserver(observer)
        }
    }
}
