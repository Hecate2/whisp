// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.webvtt.WebvttParser

data class Cue(val startMs: Long, val endMs: Long, val text: String)

@androidx.annotation.OptIn(UnstableApi::class)
object SubtitleTimeline {
    private fun time(value: String): Long {
        val parts = value.trim().replace(',', '.').split(':')
        require(parts.size in 2..3)
        val seconds = parts.last().toDouble()
        val minutes = parts[parts.lastIndex - 1].toLong()
        val hours = if (parts.size == 3) parts[0].toLong() else 0L
        return (hours * 3600000 + minutes * 60000 + seconds * 1000).toLong()
    }

    fun parse(content: String, extension: String): List<Cue> =
        if (extension.lowercase() == "lrc") lrc(content) else vtt(content)

    fun vtt(content: String): List<Cue> {
        if (content.isBlank()) return emptyList()
        val bytes = content.removePrefix("\uFEFF").toByteArray(Charsets.UTF_8)
        val result = mutableListOf<Cue>()
        WebvttParser().parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { group
            ->
            val text = group.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
            val start = group.startTimeUs / 1000
            val end = group.endTimeUs / 1000
            if (text.isNotBlank() && start >= 0 && end > start) result += Cue(start, end, text)
        }
        return result.sortedBy { it.startMs }
    }

    fun lrc(content: String): List<Cue> {
        val timestamp = Regex("\\[(\\d+:\\d+(?:[.]\\d+)?)\\]")
        val offset =
            Regex("\\[offset:([+-]?\\d+)\\]", RegexOption.IGNORE_CASE)
                .find(content)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull() ?: 0L
        val rows =
            content
                .lines()
                .flatMap { line ->
                    val text = line.replace(timestamp, "").trim()
                    if (text.isBlank()) emptyList()
                    else
                        timestamp
                            .findAll(line)
                            .mapNotNull {
                                runCatching {
                                    (time(it.groupValues[1]) + offset).coerceAtLeast(0) to text
                                }
                                    .getOrNull()
                            }
                            .toList()
                }
                .sortedBy { it.first }
        return rows.mapIndexed { index, row ->
            Cue(row.first, rows.getOrNull(index + 1)?.first ?: Long.MAX_VALUE, row.second)
        }
    }

    fun current(cues: List<Cue>, positionMs: Long): Int {
        val found = anchor(cues, positionMs)
        return found.takeIf {
            it >= 0 && positionMs >= cues[it].startMs && positionMs < cues[it].endMs
        } ?: -1
    }

    // Keep a readable context during quiet intervals, while current() retains exact timing.
    fun anchor(cues: List<Cue>, positionMs: Long): Int {
        if (cues.isEmpty()) return -1
        // An upper-bound insertion point also selects the last duplicate timestamp.
        val insertion = cues.binarySearch { if (it.startMs <= positionMs) -1 else 1 }
        return (-insertion - 2).coerceAtLeast(0)
    }
}
