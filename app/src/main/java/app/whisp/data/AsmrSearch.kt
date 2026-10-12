// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.text.Normalizer
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class AsmrFacet(val kind: String, val name: String) {
    init {
        require(kind in setOf("va", "circle", "tagw"))
        require(name.isNotBlank() && name.length <= 160 && '$' !in name)
    }

    val label
        get() =
            when (kind) {
                "va" -> "声优"
                "circle" -> "社团"
                else -> "标签"
            }

    val token
        get() = "$$kind:$name$"
}

fun normalizedAsmrInput(input: String): String =
    Normalizer.normalize(input, Normalizer.Form.NFKC).trim()

fun asmrWorkNumber(input: String): Long? {
    val text = normalizedAsmrInput(input)
    val rj = Regex("(?i)RJ([0-9]{6,8})")
    rj.matchEntire(text)?.let {
        return it.groupValues[1].toLongOrNull()?.takeIf { id -> id > 0 }
    }
    val url = text.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val host = url.host
    if (host == "asmr.one" || host.endsWith(".asmr.one")) {
        val segments = url.pathSegments
        if (segments.size == 2 && segments[0] == "work")
            return segments[1].removePrefix("RJ").toLongOrNull()?.takeIf { it > 0 }
    }
    if (host == "dlsite.com" || host.endsWith(".dlsite.com")) {
        val match = Regex("(?i)/product_id/(RJ[0-9]{6,8})\\.html/?$").find(url.encodedPath)
        return match?.groupValues?.get(1)?.let { asmrWorkNumber(it) }
    }
    return null
}

internal fun encodeAsmrQuery(query: AsmrQuery): String = buildJsonObject {
    put("source", query.sourceId)
    put("text", query.text)
    put("order", query.order)
    put("subtitles", query.subtitlesOnly)
    put(
        "facets",
        JsonArray(
            query.facets.map {
                buildJsonObject {
                    put("kind", it.kind)
                    put("name", it.name)
                }
            },
        ),
    )
}
    .toString()

internal fun decodeAsmrQuery(data: String?): AsmrQuery? = runCatching {
    val item = Json.parseToJsonElement(data ?: return null).jsonObject
    AsmrQuery(
        item.getValue("source").jsonPrimitive.content,
        item.getValue("text").jsonPrimitive.content,
        item.getValue("order").jsonPrimitive.content,
        item.getValue("subtitles").jsonPrimitive.boolean,
        item.getValue("facets").jsonArray.map {
            AsmrFacet(
                it.jsonObject.getValue("kind").jsonPrimitive.content,
                it.jsonObject.getValue("name").jsonPrimitive.content,
            )
        },
    )
}
    .getOrNull()

data class AsmrPreview(
    val sourceId: String,
    val catalogArtist: String,
    val work: AsmrWork,
    val indexed: IndexedLibrary,
) {
    val details
        get() = WorkDetails(indexed.works.single(), indexed.tracks, indexed.attachments)
}
