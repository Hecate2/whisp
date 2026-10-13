// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DlsiteFileTreeTest {
    private val fixture =
        """{
      "tree":[{"type":"folder","name":"本編","children":[{"type":"file","name":"01_示例.wav","hashname":"audio-hash"},{"type":"file","name":"介绍.txt","hashname":"text-hash"}]}],
      "playfile":{
        "audio-hash":{"type":"audio","audio":{"optimized":{"name":"audio.mp3","duration":12.5,"length":50000},"vtt":"caption-hash"}},
        "caption-hash":{"type":"text","text":{"optimized":{"name":"caption.vtt","length":1000}}},
        "text-hash":{"type":"text","text":{"optimized":{"name":"intro.txt","length":500}}}
      }
    }"""

    @Test
    fun optimizedFormatOriginalFoldersAndExplicitCaptionAssociationArePreserved() {
        val result =
            DlsiteFileTree.index(
                DlsiteOwnedWork("RJ00000001", "示例作品"),
                "account-a",
                1,
                Json.parseToJsonElement(fixture).jsonObject,
            )
        val track = result.tracks.single()
        assertEquals("本編/01_示例.mp3", track.relativePath)
        assertEquals(12500L, track.durationMs)
        val caption = result.attachments.single { it.id == track.subtitleId }
        assertEquals("subtitle", caption.kind)
        assertEquals("01_示例.vtt", caption.fileName)
        assertEquals("dlsite", result.works.single().origin)
        assertTrue(
            result.locations.all { it.uri.startsWith("dlsite-resource://RJ00000001/optimized/") },
        )
        assertEquals(3, result.locations.size)
        val otherAccount =
            DlsiteFileTree.index(
                DlsiteOwnedWork("RJ00000001", "示例作品"),
                "account-b",
                1,
                Json.parseToJsonElement(fixture).jsonObject,
            )
        assertNotEquals(track.id, otherAccount.tracks.single().id)
    }

    @Test
    fun missingCaptionResourceKeepsAudioPlayable() {
        val root = Json.parseToJsonElement(fixture).jsonObject
        val files = root.getValue("playfile").jsonObject - "caption-hash"
        val changed = JsonObject(root + ("playfile" to JsonObject(files)))
        val result =
            DlsiteFileTree.index(DlsiteOwnedWork("RJ00000001", "示例作品"), "account", 1, changed)
        assertNull(result.tracks.single().subtitleId)
        assertEquals(1, result.attachments.size)
    }

    @Test
    fun extensionlessAudioKeepsPeriodsInParentDirectoryAndCaptionPath() {
        val tree =
            Json.parseToJsonElement(
                    fixture.replace("本編", "本編.ver2").replace("01_示例.wav", "01_示例"),
                )
                .jsonObject
        val result = DlsiteFileTree.index(DlsiteOwnedWork("RJ00000001", "示例作品"), "account", 1, tree)
        assertEquals("本編.ver2/01_示例.mp3", result.tracks.single().relativePath)
        assertEquals(
            "本編.ver2/01_示例.vtt",
            result.attachments.single { it.kind == "subtitle" }.relativePath,
        )
    }

    @Test
    fun directoryResourcesCannotEscapeOfficialWorkPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            DlsiteFileTree.index(
                DlsiteOwnedWork("RJ00000001", "示例作品"),
                "account",
                1,
                Json.parseToJsonElement(fixture.replace("audio.mp3", "../../audio.mp3")).jsonObject,
            )
        }
    }
}
