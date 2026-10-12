// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

data class Cue(val startMs: Long, val endMs: Long, val text: String)

object SubtitleTimeline {
    private fun time(value: String): Long {
        val parts = value.trim().replace(',', '.').split(':')
        require(parts.size in 2..3)
        val seconds = parts.last().toDouble()
        val minutes = parts[parts.lastIndex - 1].toLong()
        val hours = if (parts.size == 3) parts[0].toLong() else 0L
        return (hours * 3600000 + minutes * 60000 + seconds * 1000).toLong()
    }
    fun parse(content: String, extension: String): List<Cue> = if (extension.lowercase() == "lrc") lrc(content) else vtt(content)
    fun vtt(content: String): List<Cue> {
        val clean = content.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val result = mutableListOf<Cue>()
        val lines = clean.lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index++]
            if ("-->" !in line) continue
            val times = line.split("-->", limit = 2)
            val start = runCatching { time(times[0]) }.getOrNull() ?: continue
            val end = runCatching { time(times[1].trim().substringBefore(' ')) }.getOrNull() ?: continue
            val text = mutableListOf<String>()
            while (index < lines.size && lines[index].isNotBlank()) text += lines[index++]
            if (end <= start || text.isEmpty()) continue
            result += Cue(start, end, text.joinToString("\n")
                .replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&nbsp;", " "))
        }
        return result.sortedBy { it.startMs }
    }
    fun lrc(content: String): List<Cue> {
        val timestamp = Regex("\\[(\\d+:\\d+(?:[.]\\d+)?)\\]")
        val offset = Regex("\\[offset:([+-]?\\d+)\\]", RegexOption.IGNORE_CASE).find(content)
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val rows = content.lines().flatMap { line ->
            val text = line.replace(timestamp, "").trim()
            if (text.isBlank()) emptyList() else timestamp.findAll(line).mapNotNull {
                runCatching { (time(it.groupValues[1]) + offset).coerceAtLeast(0) to text }.getOrNull()
            }.toList()
        }.sortedBy { it.first }
        return rows.mapIndexed { index, row -> Cue(row.first, rows.getOrNull(index + 1)?.first ?: Long.MAX_VALUE, row.second) }
    }
    fun current(cues: List<Cue>, positionMs: Long): Int {
        val found = anchor(cues, positionMs)
        return found.takeIf { it >= 0 && positionMs >= cues[it].startMs && positionMs < cues[it].endMs } ?: -1
    }
    // Keep a readable context during quiet intervals, while current() retains exact timing.
    fun anchor(cues: List<Cue>, positionMs: Long): Int {
        if (cues.isEmpty()) return -1
        var low = 0; var high = cues.size - 1; var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (cues[middle].startMs <= positionMs) { found = middle; low = middle + 1 } else high = middle - 1
        }
        return found.coerceAtLeast(0)
    }
}
