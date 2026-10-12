// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import org.junit.Assert.*
import org.junit.Test

class SubtitleTimelineTest {
    @Test
    fun quietIntervalsKeepContextAndBackwardSeekChangesIt() {
        val cues = listOf(Cue(1000, 3000, "One"), Cue(5000, 7000, "Two"))
        assertEquals(0, SubtitleTimeline.anchor(cues, 0))
        assertEquals(-1, SubtitleTimeline.current(cues, 0))
        assertEquals(0, SubtitleTimeline.anchor(cues, 4000))
        assertEquals(-1, SubtitleTimeline.current(cues, 4000))
        assertEquals(1, SubtitleTimeline.anchor(cues, 9000))
        assertEquals(0, SubtitleTimeline.anchor(cues, 2000))
        assertEquals(-1, SubtitleTimeline.anchor(emptyList(), 2000))
    }

    @Test
    fun vttKeepsMultilineAndHandlesSeekIntoSilence() {
        val source =
            "\uFEFFWEBVTT\r\n\r\ncue-1\r\n00:00:01.000 --> 00:00:03.500 align:start\r\n<v voice>轻声</v>\r\n第二行 &amp; 风声\r\n\r\n00:00:05.000 --> 00:00:06.000\r\n下一句\r\n"
        val cues = SubtitleTimeline.vtt(source)
        assertEquals(2, cues.size)
        assertEquals("轻声\n第二行 & 风声", cues[0].text)
        assertEquals(0, SubtitleTimeline.current(cues, 1200))
        assertEquals(-1, SubtitleTimeline.current(cues, 4000))
        assertEquals(1, SubtitleTimeline.current(cues, 5500))
        assertEquals(-1, SubtitleTimeline.current(cues, 6000))
    }

    @Test
    fun lrcExpandsRepeatedTimestampsAndOffset() {
        val cues =
            SubtitleTimeline.lrc("[ar:voice]\n[offset:250]\n[00:01.20][00:05.00]轻声\n[00:09.000]晚安")
        assertEquals(listOf(1450L, 5250L, 9250L), cues.map { it.startMs })
        assertEquals(5250L, cues[0].endMs)
        assertEquals(1, SubtitleTimeline.current(cues, 5500))
    }

    @Test
    fun malformedCueDoesNotBreakRestOfFile() {
        val cues =
            SubtitleTimeline.vtt(
                "WEBVTT\n\nwrong --> 00:02.000\n坏字幕\n\n00:03.000 --> 00:04.000\n正确字幕\n",
            )
        assertEquals(1, cues.size)
        assertEquals("正确字幕", cues.single().text)
    }
}
