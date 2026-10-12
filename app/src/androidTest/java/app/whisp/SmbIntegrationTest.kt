// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.Manifest
import android.content.ComponentName
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.whisp.data.SmbDataSource
import app.whisp.playback.PlaybackService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Run only in the isolated smoke application, against a read-only test share. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SmbIntegrationTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val ui = ActivityScenarioRule(MainActivity::class.java)
    private lateinit var activity: MainActivity

    @Before
    fun captureActivity() {
        ui.scenario.onActivity { activity = it }
    }

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val repo
        get() = (activity.application as WhispApp).repository

    private fun waitFor(timeoutMs: Long = 30000, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail("Condition timed out")
    }

    @Test
    fun scanSeekBackgroundDownloadAndOfflineWithoutSource() {
        assertTrue(
            "Use -PsmokeTest to preserve the installed app",
            repo.context.packageName.endsWith(".smoketest"),
        )
        val args = InstrumentationRegistry.getArguments()
        val root = requireNotNull(args.getString("smbRoot"))
        val username = requireNotNull(args.getString("smbUser"))
        val password = requireNotNull(args.getString("smbPassword"))
        val id = runBlocking { repo.addSmb("SMB 测试", root, username, password) }
        waitFor(60000) {
            runBlocking { repo.dao.source(id)!!.lastScan > 0 } && id !in repo.busy.value
        }
        waitFor { repo.library.value.any { it.work.rj == "RJ00000001" } }
        val detail = repo.library.value.first { it.work.rj == "RJ00000001" }
        assertEquals(1, detail.tracks.size)
        val track = detail.tracks.single()
        assertTrue(track.durationMs in 1_850_000..1_854_000)
        assertNotNull(repo.artwork(detail.work.coverId))
        assertTrue(runBlocking { repo.readAsset(track.subtitleId!!) }.isNotEmpty())
        assertTrue(detail.attachments.any { it.fileName.endsWith(".txt") })
        // Exercise real Media3 offset/length boundaries through the SMB adapter.
        val source = runBlocking { repo.dao.source(id)!! }
        val uri = runBlocking { repo.resolve(track.id) }
        val data = SmbDataSource { repo.smb.open(source, password, it) }
        val offset = 40_000_003L
        assertEquals(
            1021L,
            data.open(
                androidx.media3.datasource.DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(offset)
                    .setLength(1021)
                    .build(),
            ),
        )
        val actual = ByteArray(1021)
        var used = 0
        while (used < actual.size) used += data.read(actual, used, actual.size - used)
        assertEquals(-1, data.read(actual, 0, 1))
        data.close()
        assertArrayEquals(repo.smb.range(uri, source, password, offset, actual.size), actual)
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
            val item = runBlocking { repo.mediaItem(track, detail.work) }
            instrumentation.runOnMainSync {
                assertTrue(
                    "Muted playback requires volume command",
                    controller.isCommandAvailable(Player.COMMAND_SET_VOLUME),
                )
                controller.volume = 0f
                controller.setMediaItem(item)
                controller.prepare()
                controller.play()
            }
            fun playing(): Boolean {
                var value = false
                instrumentation.runOnMainSync { value = controller.isPlaying }
                return value
            }
            waitFor { playing() }
            instrumentation.runOnMainSync { controller.seekTo(15 * 60_000L) }
            waitFor {
                var position = 0L
                instrumentation.runOnMainSync { position = controller.currentPosition }
                position >= 15 * 60_000L && playing()
            }
            instrumentation.runOnMainSync { activity.moveTaskToBack(true) }
            Thread.sleep(1500)
            assertTrue("SMB playback continues in the background", playing())
            instrumentation.runOnMainSync { controller.pause() }
            runBlocking { repo.downloadWork(detail.work.id) }
            waitFor(180000) { repo.downloads.value[track.id]?.state == Download.STATE_COMPLETED }
            waitFor { runBlocking { repo.dao.details(detail.work.id)!!.work.offlineAttachments } }
            runBlocking { repo.removeSource(id) }
            assertNull(runBlocking { repo.dao.source(id) })
            val offlineItem = runBlocking { repo.mediaItem(track, detail.work) }
            instrumentation.runOnMainSync {
                controller.setMediaItem(offlineItem, 20 * 60_000L)
                controller.prepare()
                controller.play()
            }
            waitFor { playing() }
            assertTrue(runBlocking { repo.readAsset(track.subtitleId!!) }.isNotEmpty())
            assertTrue(repo.isOffline(runBlocking { repo.dao.details(detail.work.id)!! }))
            instrumentation.runOnMainSync { controller.pause() }
        } finally {
            instrumentation.runOnMainSync { MediaController.releaseFuture(future) }
        }
    }
}
