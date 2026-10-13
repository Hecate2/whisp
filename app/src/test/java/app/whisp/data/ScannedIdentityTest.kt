// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import org.junit.Assert.*
import org.junit.Test

class ScannedIdentityTest {
    private val source = "source"
    private val root = "RJ00000001 Example"

    private fun file(name: String, size: Long = 100, folder: String = root, uri: String = name) =
        FileEntry("$folder/$name", "content://example/$uri", false, size)

    private fun index(vararg files: FileEntry) = LibraryIndexer.index(files.toList(), source, 1)

    private fun preserve(fresh: IndexedLibrary, previous: IndexedLibrary) =
        preserveScanIdentity(fresh, previous.works, previous.tracks, previous.locations, source)

    @Test
    fun addingAudioRetainsWorkAndUnchangedTrackIdentities() {
        val first = file("01_Intro.mp3")
        val old = index(first)
        val fresh = index(first, file("02_Chapter.mp3"))
        assertNotEquals(old.works.single().id, fresh.works.single().id)
        val saved = preserve(fresh, old)
        assertEquals(old.works.single().id, saved.works.single().id)
        assertEquals(old.tracks.single().id, saved.tracks.first().id)
        assertEquals(2, saved.tracks.size)
        assertEquals(
            stableId("${old.works.single().id}|02_Chapter.mp3"),
            saved.tracks.last().id,
        )
    }

    @Test
    fun removingAudioRetainsTheRemainingTrackAndWorkIdentities() {
        val first = file("01_Intro.mp3")
        val old = index(first, file("02_Chapter.mp3"))
        val saved = preserve(index(first), old)
        assertEquals(old.works.single().id, saved.works.single().id)
        assertEquals(old.tracks.first().id, saved.tracks.single().id)
    }

    @Test
    fun subtitleScriptCoverAndLocationReferencesFollowThePreservedWork() {
        val first = file("01_Intro.mp3")
        val caption = file("01_Intro.mp3.vtt")
        val script = file("01_Intro.txt")
        val cover = file("cover.jpg")
        val old = index(first, caption, script, cover)
        val saved = preserve(index(first, caption, script, cover, file("02_Chapter.mp3")), old)
        val work = saved.works.single()
        val track = saved.tracks.first()
        val attachments = saved.attachments.associateBy { it.id }
        assertEquals(old.works.single().coverId, work.coverId)
        assertEquals("subtitle", attachments.getValue(track.subtitleId!!).kind)
        assertEquals("script", attachments.getValue(track.scriptId!!).kind)
        assertTrue(saved.tracks.all { it.workId == work.id })
        assertTrue(saved.attachments.all { it.workId == work.id })
        val assets = (saved.tracks.map { it.id } + saved.attachments.map { it.id }).toSet()
        assertEquals(assets, saved.locations.map { it.assetId }.toSet())
        saved.locations.forEach { location ->
            assertEquals(
                stableId("${location.sourceId}|${location.assetId}|${location.uri}"),
                location.id,
            )
        }
    }

    @Test
    fun differentWorkCodeOrRecordingPathKeepsTheNewEdition() {
        val old = index(file("jp/01_Intro.mp3", uri = "shared"))
        val changedCode =
            index(
                file("jp/01_Intro.mp3", folder = "RJ00000002 Example", uri = "shared"),
                file("jp/02_Chapter.mp3", folder = "RJ00000002 Example"),
            )
        val changedEdition =
            index(file("zh/01_Intro.mp3", uri = "shared"), file("zh/02_Chapter.mp3"))
        assertEquals(changedCode, preserve(changedCode, old))
        assertEquals(changedEdition, preserve(changedEdition, old))
    }

    @Test
    fun changedOrUnknownSizeOfAnyOverlappingAudioPreventsMerging() {
        val first = file("01_Intro.mp3")
        val second = file("02_Chapter.mp3")
        val old = index(first, second)
        for (size in listOf(0L, 200L)) {
            val changed = index(first, second.copy(size = size), file("03_Chapter.mp3"))
            assertEquals(changed, preserve(changed, old))
        }
        val unknownOld = index(first.copy(size = 0))
        val changed = index(first, second)
        assertEquals(changed, preserve(changed, unknownOld))
    }

    @Test
    fun anotherSourceAndAnOnlineOriginCannotEstablishContinuity() {
        val first = file("01_Intro.mp3")
        val old = index(first)
        val fresh = index(first, file("02_Chapter.mp3"))
        assertEquals(
            fresh,
            preserve(
                fresh,
                old.copy(locations = old.locations.map { it.copy(sourceId = "other") }),
            ),
        )
        assertEquals(
            fresh,
            preserve(fresh, old.copy(works = old.works.map { it.copy(origin = "dlsite") })),
        )
    }

    @Test
    fun oneOldWorkCannotMergeTwoNewDirectories() {
        val first = file("A/01_Intro.mp3", uri = "first")
        val second = file("B/02_Chapter.mp3", uri = "second")
        val old = index(first, second)
        val fresh =
            index(
                file("A/01_Intro.mp3", folder = "RJ00000001 First", uri = "first"),
                file("B/02_Chapter.mp3", folder = "RJ00000001 Second", uri = "second"),
            )
        assertEquals(2, fresh.works.size)
        assertEquals(fresh, preserve(fresh, old))
    }

    @Test
    fun ambiguousOldUriCannotMergeEditions() {
        val old =
            index(
                file("01_Intro.mp3", folder = "RJ00000001 First", uri = "shared"),
                file("01_Intro.mp3", size = 200, folder = "RJ00000001 Second", uri = "shared"),
            )
        val fresh = index(file("01_Intro.mp3", uri = "shared"), file("02_Chapter.mp3"))
        assertEquals(2, old.works.size)
        assertEquals(fresh, preserve(fresh, old))
    }

    @Test
    fun sharedNewUriAndMultipleOldWorkAnchorsRemainSeparate() {
        val first = file("01_Intro.mp3", uri = "first")
        val old = index(first)
        val shared = index(first, file("02_Chapter.mp3", uri = "first"))
        assertEquals(shared, preserve(shared, old))

        val other = index(file("02_Chapter.mp3", folder = "RJ00000001 Other", uri = "second"))
        val combinedOld =
            old.copy(
                works = old.works + other.works,
                tracks = old.tracks + other.tracks,
                locations = old.locations + other.locations,
            )
        val merged = index(first, file("02_Chapter.mp3", uri = "second"), file("03_Chapter.mp3"))
        assertEquals(merged, preserve(merged, combinedOld))
    }

    @Test
    fun existingContentIdentityWinsOverThePreviousDirectoryAnchor() {
        val first = file("01_Intro.mp3")
        val old = index(first)
        val fresh = index(first, file("02_Chapter.mp3"))
        val existing = fresh.copy(locations = fresh.locations.map { it.copy(sourceId = "other") })
        val previous =
            old.copy(
                works = old.works + existing.works,
                tracks = old.tracks + existing.tracks,
                locations = old.locations + existing.locations,
            )
        assertEquals(fresh, preserve(fresh, previous))
    }
}
