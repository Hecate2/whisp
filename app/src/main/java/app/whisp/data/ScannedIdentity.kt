// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

/**
 * Keep a continuously scanned edition's identity when its unchanged audio gains or loses tracks.
 */
fun preserveScanIdentity(
    indexed: IndexedLibrary,
    previousWorks: List<WorkEntity>,
    previousTracks: List<TrackEntity>,
    previousLocations: List<LocationEntity>,
    sourceId: String,
): IndexedLibrary {
    val oldWorks = previousWorks.filter { it.origin == "local" }.associateBy { it.id }
    val oldTracks = previousTracks.filter { it.workId in oldWorks }.associateBy { it.id }
    val newWorks = indexed.works.associateBy { it.id }
    val newTracks = indexed.tracks.associateBy { it.id }
    val oldLocations =
        previousLocations
            .filter { it.sourceId == sourceId && it.assetId in oldTracks }
            .groupBy { it.uri }
    val newLocations =
        indexed.locations
            .filter { it.sourceId == sourceId && it.assetId in newTracks }
            .groupBy { it.uri }
    val candidates = mutableMapOf<String, MutableSet<String>>()
    val uncertain = mutableSetOf<String>()
    for ((uri, freshLocations) in newLocations) {
        val previous = oldLocations[uri] ?: continue
        val fresh = freshLocations.distinctBy { it.assetId to it.size }
        val old = previous.distinctBy { it.assetId to it.size }
        if (fresh.size != 1 || old.size != 1) {
            uncertain += freshLocations.mapNotNull { newTracks[it.assetId]?.workId }
            continue
        }
        val freshLocation = fresh.single()
        val oldLocation = old.single()
        val freshTrack = newTracks.getValue(freshLocation.assetId)
        val oldTrack = oldTracks.getValue(oldLocation.assetId)
        val freshWork = newWorks[freshTrack.workId] ?: continue
        val oldWork = oldWorks.getValue(oldTrack.workId)
        if (
            freshWork.origin != "local" ||
                freshWork.rj != oldWork.rj ||
                freshTrack.relativePath != oldTrack.relativePath ||
                freshLocation.size <= 0 ||
                oldLocation.size <= 0 ||
                freshLocation.size != oldLocation.size
        ) {
            uncertain += freshWork.id
            continue
        }
        candidates.getOrPut(freshWork.id) { mutableSetOf() }.add(oldWork.id)
    }
    val proposals =
        candidates
            .filter { (workId, matches) -> workId !in uncertain && matches.size == 1 }
            .mapValues { (_, matches) -> matches.single() }
    val counts = candidates.values.flatMap { it }.groupingBy { it }.eachCount()
    val claimed = indexed.works.map { it.id }.filter { it in oldWorks }.toSet()
    val aliases = proposals.filter { (fresh, old) ->
        fresh !in oldWorks && old !in claimed && counts[old] == 1
    }
    if (aliases.isEmpty()) return indexed

    val assetAliases = buildMap {
        indexed.tracks.forEach { track ->
            aliases[track.workId]?.let { old ->
                put(track.id, stableId("$old|${track.relativePath}"))
            }
        }
        indexed.attachments.forEach { attachment ->
            aliases[attachment.workId]?.let { old ->
                put(attachment.id, stableId("$old|${attachment.relativePath}"))
            }
        }
    }
    return indexed.copy(
        works =
            indexed.works.map { work ->
                aliases[work.id]?.let { old ->
                    work.copy(id = old, coverId = assetAliases[work.coverId] ?: work.coverId)
                } ?: work
            },
        tracks =
            indexed.tracks.map { track ->
                aliases[track.workId]?.let { old ->
                    track.copy(
                        id = assetAliases.getValue(track.id),
                        workId = old,
                        subtitleId = assetAliases[track.subtitleId] ?: track.subtitleId,
                        scriptId = assetAliases[track.scriptId] ?: track.scriptId,
                    )
                } ?: track
            },
        attachments =
            indexed.attachments.map { attachment ->
                aliases[attachment.workId]?.let { old ->
                    attachment.copy(id = assetAliases.getValue(attachment.id), workId = old)
                } ?: attachment
            },
        locations =
            indexed.locations.map { location ->
                assetAliases[location.assetId]?.let { asset ->
                    location.copy(
                        id = stableId("${location.sourceId}|$asset|${location.uri}"),
                        assetId = asset,
                    )
                } ?: location
            },
    )
}
