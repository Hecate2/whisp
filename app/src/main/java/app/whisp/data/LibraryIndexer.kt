// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.security.MessageDigest

data class FileEntry(val path: String, val uri: String, val directory: Boolean,
                     val size: Long = 0, val modified: Long = 0)
data class IndexedLibrary(val works: List<WorkEntity>, val tracks: List<TrackEntity>,
                          val attachments: List<AttachmentEntity>, val locations: List<LocationEntity>)

fun stableId(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    val hex = "0123456789abcdef"
    return buildString(32) { for (i in 0 until 16) {
        val byte = bytes[i].toInt() and 255
        append(hex[byte ushr 4]); append(hex[byte and 15])
    } }
}

object LibraryIndexer {
    private val rjPattern = Regex("RJ\\d{6,}", RegexOption.IGNORE_CASE)
    private val audioExtensions = setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac")
    private val images = setOf("jpg", "jpeg", "png", "webp")
    private fun String.extension() = substringAfterLast('.', "").lowercase()
    private fun String.basename() = substringAfterLast('/')
    private fun stem(name: String) = name.removeSuffix(".vtt").removeSuffix(".lrc")
        .replace(Regex("\\.(mp3|m4a|flac|wav|ogg|opus|aac|txt|jpg|jpeg|png|webp|pdf)$", RegexOption.IGNORE_CASE), "")
    fun number(name: String): Int = Regex("^(\\d+)").find(name
        .replace(Regex("^[A-Za-z]\\d+[_-]"), ""))?.groupValues?.get(1)?.toIntOrNull() ?: 9999
    fun trackTitle(name: String): String = stem(name)
        .replace(Regex("^[A-Za-z]\\d+[_-]"), "")
        .replace(Regex("^\\d+[_ .-]*"), "").trim().ifBlank { stem(name) }
    private fun rootOf(path: String): String {
        val dirs = path.split('/').dropLast(1)
        val matches = dirs.mapIndexedNotNull { index, name ->
            rjPattern.find(name)?.value?.uppercase()?.let { index to it }
        }
        if (matches.isEmpty()) return dirs.joinToString("/")
        val lastId = matches.last().second
        val index = matches.lastOrNull { it.second != lastId }?.first?.let { previous ->
            matches.first { it.first > previous && it.second == lastId }.first
        } ?: matches.first().first
        return dirs.take(index + 1).joinToString("/")
    }
    fun index(files: List<FileEntry>, sourceId: String, generation: Long): IndexedLibrary {
        // Determine each file's work once, instead of walking every path again for every work.
        val fileGroups = files.filterNot { it.directory }.groupBy { rootOf(it.path) }
        val groups = fileGroups.mapValues { (_, entries) -> entries.filter { it.path.extension() in audioExtensions } }
            .filterValues { it.isNotEmpty() }
        val works = mutableListOf<WorkEntity>()
        val tracks = mutableListOf<TrackEntity>()
        val attachments = mutableListOf<AttachmentEntity>()
        val locations = mutableListOf<LocationEntity>()
        groups.toSortedMap().forEach { (root, group) ->
            val name = root.basename().ifBlank { "未命名作品" }
            val rj = rjPattern.find(name)?.value?.uppercase()
            val audioSignature = group.map { "${it.path.removePrefix("$root/")}:${it.size}" }.sorted()
            val workId = stableId("${rj ?: name}|${audioSignature.joinToString("|")}")
            val inWork = fileGroups.getValue(root)
            val resourceFiles = inWork.filter { it.path.extension() !in audioExtensions &&
                it.path.extension() in images + setOf("vtt", "lrc", "txt", "pdf") }
            val resources = resourceFiles.associateWith { file ->
                val relative = file.path.removePrefix("$root/")
                val fileName = file.path.basename()
                val kind = when (file.path.extension()) {
                    "vtt", "lrc" -> "subtitle"
                    "txt" -> if (Regex("附赠|特典|おまけ|omake|bonus", RegexOption.IGNORE_CASE).containsMatchIn(relative.substringBeforeLast('/'))) "extra-text" else "script"
                    in images -> "image"
                    else -> "document"
                }
                AttachmentEntity(stableId("$workId|$relative"), workId,
                    trackTitle(fileName), fileName, relative, kind)
            }
            val cover = resources.entries.filter { it.value.kind == "image" }.maxByOrNull {
                val n = it.key.path.lowercase()
                when {
                    ("封面" in n || "cover" in n || "folder" in n) && "介绍" !in n && "back" !in n -> 100
                    "main" in n -> 70
                    "插画" in n -> 30
                    "角色" in n -> 0
                    else -> 10
                }
            }?.value?.id
            val title = name.replace(rjPattern, "")
                .replace(Regex("【[^】]*】|\\[[^]]*]"), "").trim().ifBlank { name }
            val series = root.split('/').dropLast(1).lastOrNull()?.takeUnless { rjPattern.containsMatchIn(it) }
                ?.replace(Regex("^[「『]|[」』]系列$"), "")
            works += WorkEntity(workId, rj, title, name, series = series, coverId = cover)
            fun addLocation(file: FileEntry, id: String) {
                locations += LocationEntity(stableId("$sourceId|${file.uri}"), id,
                    sourceId, file.uri, file.size, file.modified, generation)
            }
            resources.forEach { (file, attachment) -> attachments += attachment; addLocation(file, attachment.id) }
            group.sortedWith(compareBy<FileEntry> { number(it.path.basename()) }.thenBy { it.path }).forEach { file ->
                val fileName = file.path.basename()
                val relative = file.path.removePrefix("$root/")
                val sameName = resources.entries.filter { stem(it.key.path.basename()) == stem(fileName) }
                // Exact stem wins; script folders sometimes prefix the work's code.
                fun match(kind: String) = sameName.filter { it.value.kind == kind }.minByOrNull {
                    if (it.key.path.substringBeforeLast('/') == file.path.substringBeforeLast('/')) 0 else 1
                }?.value?.id
                val subtitle = sameName.filter { it.value.kind == "subtitle" }.minByOrNull {
                    if (it.key.path.extension() == "vtt") 0 else 1
                }?.value?.id
                val track = TrackEntity(stableId("$workId|$relative"), workId, trackTitle(fileName),
                    fileName, relative, number(fileName), subtitleId = subtitle, scriptId = match("script"),
                    bonus = Regex("附赠|おまけ|omake|bonus", RegexOption.IGNORE_CASE).containsMatchIn(relative))
                tracks += track
                addLocation(file, track.id)
            }
        }
        return IndexedLibrary(works, tracks, attachments, locations)
    }
}
