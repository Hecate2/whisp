// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SubtitleTimelineTest {
    @Test
    fun subripKeepsMillisecondsMultilineAndSeekBoundaries() {
        val cues =
            SubtitleTimeline.parse(
                "\uFEFF1\r\n00:00:01,250 --> 00:00:03,500\r\n<b>示例</b>\r\n第二行\r\n\r\n2\r\n00:00:05,000 --> 00:00:06,000\r\n下一句\r\n",
                "SRT",
            )
        assertEquals(2, cues.size)
        assertEquals(Cue(1250, 3500, "示例\n第二行"), cues.first())
        assertEquals(0, SubtitleTimeline.current(cues, 1250))
        assertEquals(-1, SubtitleTimeline.current(cues, 3500))
        assertEquals(1, SubtitleTimeline.current(cues, 5500))
    }

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
    fun lrcKeepsParallelLinesAtTheSameTimestamp() {
        val cues = SubtitleTimeline.lrc("[00:01.00]Original\n[00:01.00]Translation\n[00:03.00]Next")
        assertEquals(2, cues.size)
        assertEquals(Cue(1000, 3000, "Original\nTranslation"), cues.first())
        assertEquals(0, SubtitleTimeline.current(cues, 1500))
    }

    @Test
    fun lrcOffsetsClampedToZeroRetainEveryLineAndProduceValidIntervals() {
        val cues =
            SubtitleTimeline.lrc("[offset:-2000]\n[00:00.50]One\n[00:01.00]Two\n[00:03.00]Three")
        assertEquals(Cue(0, 1000, "One\nTwo"), cues.first())
        assertTrue(cues.all { it.endMs > it.startMs })
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

    @Test
    fun webvttCommentsStylesAndEntitiesUseStandardParsing() {
        val cues =
            SubtitleTimeline.vtt(
                """
                WEBVTT

                NOTE
                00:00:01.000 --> 00:00:02.000
                Comment text

                STYLE
                ::cue { color: white; }

                cue-1
                00:00:03.000 --> 00:00:04.000
                <b>Text</b> &lt; &gt; &amp; &nbsp;
                """
                    .trimIndent(),
            )
        assertEquals(1, cues.size)
        assertEquals(3000L, cues.single().startMs)
        assertEquals("Text < > &  ", cues.single().text)
    }

    @Test
    fun overlappingCuesKeepBothLinesAndTheirBoundaries() {
        val cues =
            SubtitleTimeline.vtt(
                """
                WEBVTT

                00:00:01.000 --> 00:00:03.000
                One

                00:00:02.000 --> 00:00:04.000
                Two
                """
                    .trimIndent(),
            )
        assertEquals(listOf(1000L, 2000L, 3000L), cues.map { it.startMs })
        assertEquals(listOf(2000L, 3000L, 4000L), cues.map { it.endMs })
        assertEquals(listOf("One", "One\nTwo", "Two"), cues.map { it.text })
        assertEquals(1, SubtitleTimeline.current(cues, 2500))
        assertEquals(-1, SubtitleTimeline.current(cues, 4000))
    }

    @Test
    fun anchorSupportsDuplicateTimestampsAndMaximumPosition() {
        val cues = listOf(Cue(1000, 2000, "One"), Cue(1000, 3000, "Two"))
        assertEquals(0, SubtitleTimeline.anchor(cues, 0))
        assertEquals(1, SubtitleTimeline.anchor(cues, 1000))
        assertEquals(1, SubtitleTimeline.anchor(cues, Long.MAX_VALUE))
    }
}
