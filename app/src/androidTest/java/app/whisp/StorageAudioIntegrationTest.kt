// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/** Exercises the production SMB/cache stack without competing with other apps' audio focus. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class StorageAudioIntegrationTest {
    @Test fun clearingCommittedAudioCachePreservesPlaybackPauseResumeAndSeek() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repo = (instrumentation.targetContext.applicationContext as WhispApp).repository
        assertTrue(repo.context.packageName.endsWith(".smoketest"))
        val args = InstrumentationRegistry.getArguments()
        val path = String(Base64.getUrlDecoder().decode(requireNotNull(args.getString("assetPath64"))), Charsets.UTF_8)
        val source = SourceEntity("audio-cache-source", "Cache", "smb", SmbAddress.parse(requireNotNull(args.getString("smbRoot"))).normalized,
            requireNotNull(args.getString("smbUser")), repo.secrets.encrypt(requireNotNull(args.getString("smbPassword"))))
        runBlocking { repo.dao.putSource(source); repo.storageUsage() }
        val uri = SmbAddress.assetUri(source.id, path)
        val key = "cache-audio-track"
        // Commit a real span before playback; clearing an empty cache would prove little.
        val data = repo.playbackFactory.createDataSource()
        var cached = 0L
        try {
            data.open(DataSpec.Builder().setUri(uri).setKey(key).setLength(MIB).build())
            val buffer = ByteArray(256 * 1024)
            while (true) { val n = data.read(buffer, 0, buffer.size); if (n < 0) break; cached += n }
            assertTrue("Storage includes files still being written", runBlocking { repo.storageUsage().streaming } >= MIB / 2)
        } finally { data.close() }
        assertEquals(MIB, cached)
        assertTrue(runBlocking { repo.storageUsage().streaming } >= MIB)
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(repo.context).setMediaSourceFactory(DefaultMediaSourceFactory(repo.playbackFactory)).build()
            player.setAudioAttributes(AudioAttributes.DEFAULT, false)
            player.volume = 0f
            player.setMediaItem(MediaItem.Builder().setUri(Uri.parse(uri)).setCustomCacheKey(key).build())
            player.prepare(); player.play()
        }
        fun waitFor(message: String, condition: () -> Boolean) {
            val end = System.currentTimeMillis() + 60000
            while (System.currentTimeMillis() < end) {
                var ready = false
                instrumentation.runOnMainSync { ready = condition() }
                if (ready) return
                Thread.sleep(100)
            }
            var diagnostic = ""
            instrumentation.runOnMainSync { diagnostic = "state=${player.playbackState}, error=${player.playerError?.errorCodeName}, position=${player.currentPosition}" }
            fail("$message: $diagnostic")
        }
        try {
            waitFor("initial playback") { player.isPlaying && player.currentPosition > 0 }
            var before = 0L
            instrumentation.runOnMainSync { before = player.currentPosition }
            runBlocking { repo.clearTemporaryCache(); repo.setSettings(streamingCacheMiB = 64) }
            waitFor("playback after clear") { player.isPlaying && player.currentPosition > before + 700 }
            instrumentation.runOnMainSync { player.pause() }
            waitFor("pause") { !player.playWhenReady }
            var paused = 0L
            instrumentation.runOnMainSync { paused = player.currentPosition }
            Thread.sleep(400)
            instrumentation.runOnMainSync { assertTrue("Pause must stop advancement", kotlin.math.abs(paused - player.currentPosition) <= 50); player.seekTo(6000) }
            waitFor("seek after clear") { player.playbackState == Player.STATE_READY && player.currentPosition == 6000L }
            instrumentation.runOnMainSync { player.play() }
            waitFor("resume after seek") { player.isPlaying && player.currentPosition > 6500 }
            instrumentation.runOnMainSync { assertNull(player.playerError); assertEquals(0, player.currentMediaItemIndex) }
        } finally { instrumentation.runOnMainSync { player.release() } }
    }
}
