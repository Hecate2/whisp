// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

data class TrackSection(val key: String, val title: String, val bonus: Boolean, val tracks: List<TrackEntity>)

/** Keep directory editions separate: equal names do not prove equal recordings. */
object TrackSections {
    private val bonusFolder = Regex("附赠|特典|おまけ|omake|bonus|extra", RegexOption.IGNORE_CASE)
    fun groups(tracks: List<TrackEntity>): List<TrackSection> {
        val parents = tracks.map { it.relativePath.substringBeforeLast('/', "") }
        val components = parents.map { it.split('/').filter(String::isNotBlank) }
        val common = if (components.isEmpty()) 0 else components.minOf { it.size }.let { limit ->
            (0 until limit).firstOrNull { i -> components.any { it[i] != components.first()[i] } } ?: limit
        }
        return tracks.groupBy { track ->
            val parent = track.relativePath.substringBeforeLast('/', "")
            (track.bonus || bonusFolder.containsMatchIn(parent)) to parent
        }.map { (key, rows) ->
            val path = key.second.split('/').filter(String::isNotBlank).drop(common).joinToString(" / ")
            TrackSection("${key.first}:${key.second}", path.ifBlank { if (key.first) "附赠" else "正篇" }, key.first,
                rows.sortedWith(compareBy({ it.order }, { it.relativePath })))
        }.sortedWith(compareBy({ it.bonus }, { it.title.lowercase() != "mp3" }, { it.key }))
    }
    fun queue(tracks: List<TrackEntity>, selectedId: String? = null): List<TrackEntity> {
        val sections = groups(tracks)
        return (sections.firstOrNull { it.tracks.any { t -> t.id == selectedId } } ?: sections.firstOrNull())?.tracks.orEmpty()
    }
}
