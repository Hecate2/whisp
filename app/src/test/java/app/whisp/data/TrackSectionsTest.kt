// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import org.junit.Assert.*
import org.junit.Test

class TrackSectionsTest {
    private fun track(id: String, path: String, order: Int, bonus: Boolean = false) =
        TrackEntity(id, "work", id, path.substringAfterLast('/'), path, order, bonus = bonus)

    @Test
    fun regularAndBonusTracksInTheSameFolderRemainSeparate() {
        val regular = track("a", "mp3/01.mp3", 1)
        val bonus = track("b", "mp3/07_附赠.mp3", 7, true)
        assertEquals(
            listOf("正篇", "附赠"),
            TrackSections.groups(listOf(bonus, regular)).map { it.title },
        )
        assertEquals(listOf(regular), TrackSections.queue(listOf(bonus, regular)))
        assertEquals(listOf(bonus), TrackSections.queue(listOf(bonus, regular), "b"))
    }

    @Test
    fun sameNamedRecordingsInDifferentDirectoriesAreNotMergedOrMixed() {
        val mp3 = track("mp3", "mp3/01.mp3", 1)
        val wav = track("wav", "wav/01.wav", 1)
        val bonus = track("bonus", "特典/01.mp3", 1)
        val tracks = listOf(wav, bonus, mp3)
        assertEquals(3, TrackSections.groups(tracks).size)
        assertEquals(listOf(mp3), TrackSections.queue(tracks))
        assertEquals(listOf(wav), TrackSections.queue(tracks, "wav"))
        assertEquals(listOf(bonus), TrackSections.queue(tracks, "bonus"))
    }

    @Test
    fun commonWrappersDoNotClutterGroupNamesAndNumericOrderIsPreserved() {
        val tracks =
            listOf(
                track("2", "wrapper/mp3/02.mp3", 2),
                track("1", "wrapper/mp3/01.mp3", 1),
                track("extra", "wrapper/omake/01.mp3", 1),
            )
        assertEquals(listOf("mp3", "omake"), TrackSections.groups(tracks).map { it.title })
        assertEquals(listOf("1", "2"), TrackSections.queue(tracks).map { it.id })
        assertEquals(listOf("1", "2"), TrackSections.queue(tracks, "removed").map { it.id })
    }
}
