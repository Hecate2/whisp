// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class AsmrWork(
    val id: Long,
    val title: String,
    val rj: String,
    val artist: String,
    val coverUrl: String?,
) {
    val workId
        get() = stableId("asmr.one|work|$id")

    val coverId
        get() = coverUrl?.let { stableId("$workId|_asmr-cover/main.jpg") }

    fun entity() = WorkEntity(workId, rj, title, title, artist = artist, coverId = coverId)
}

data class AsmrPage(val works: List<AsmrWork>, val hasMore: Boolean)

class AsmrOneClient(
    private val http: OkHttpClient,
    private val root: HttpUrl = API_ROOT.toHttpUrl(),
) {
    companion object {
        const val API_ROOT = "https://api.asmr.one/"
        val MEDIA_HEADERS = mapOf("Origin" to "https://asmr.one", "Referer" to "https://asmr.one/")
    }

    suspend fun catalog(
        page: Int,
        query: String = "",
        order: String = "create_date",
        subtitlesOnly: Boolean = false,
    ): AsmrPage {
        require(page > 0 && order in setOf("create_date", "dl_count"))
        val url = root.newBuilder().addPathSegment("api")
        if (query.isBlank()) url.addPathSegment("works")
        else url.addPathSegment("search").addPathSegment(query.trim())
        url.addQueryParameter("page", page.toString())
            .addQueryParameter("pageSize", "20")
            .addQueryParameter("order", order)
            .addQueryParameter("sort", "desc")
            .addQueryParameter("subtitle", if (subtitlesOnly) "1" else "0")
        val body = json(url.build()).jsonObject
        val works = body["works"]?.jsonArray ?: throw IOException("作品列表格式错误")
        val items = works.map { parseWork(it.jsonObject) }.distinctBy { it.id }
        val total = body["pagination"]?.jsonObject?.get("totalCount")?.jsonPrimitive?.longOrNull
        return AsmrPage(
            items,
            works.isNotEmpty() && (total?.let { page * 20L < it } ?: (works.size == 20)),
        )
    }

    suspend fun work(id: Long): AsmrWork {
        require(id > 0)
        return parseWork(
            json(
                    root
                        .newBuilder()
                        .addPathSegments("api/workInfo")
                        .addPathSegment(id.toString())
                        .build(),
                )
                .jsonObject,
        )
    }

    suspend fun index(work: AsmrWork, sourceId: String, generation: Long): IndexedLibrary {
        val tree =
            json(
                    root
                        .newBuilder()
                        .addPathSegments("api/tracks")
                        .addPathSegment(work.id.toString())
                        .build(),
                )
                .jsonArray
        val files = mutableListOf<FileEntry>()
        val durations = mutableMapOf<String, Long>()
        val paths = mutableSetOf<String>()
        var visited = 0
        fun walk(nodes: JsonArray, parent: String, depth: Int) {
            require(depth <= 32) { "作品目录层级过多" }
            for (element in nodes) {
                require(++visited <= 20000) { "作品文件数量过多" }
                val node = element.jsonObject
                val name = node.text("title").replace(Regex("[\\\\/\\u0000]"), "_").trim()
                if (name.isEmpty() || name in setOf(".", "..")) continue
                val path = "$parent/$name"
                val children = node["children"] as? JsonArray
                if (node.text("type") == "folder") {
                    if (children != null) walk(children, path, depth + 1)
                    continue
                }
                val raw = node.text("mediaStreamUrl").ifBlank { node.text("mediaDownloadUrl") }
                if (raw.isBlank()) continue
                val url = resourceUrl(raw)
                val audio = node.text("type") == "audio"
                val filePath = if (audio && '.' !in name) "$path.mp3" else path
                require(paths.add(filePath)) { "作品目录存在重复文件名" }
                files +=
                    FileEntry(
                        filePath,
                        url.toString(),
                        false,
                        node["size"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0) ?: 0,
                    )
                if (audio) {
                    val seconds = node["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    if (seconds.isFinite() && seconds > 0)
                        durations[filePath.removePrefix("${work.rj}/")] = (seconds * 1000).toLong()
                }
            }
        }
        walk(tree, work.rj, 0)
        work.coverUrl?.let {
            files += FileEntry("${work.rj}/_asmr-cover/main.jpg", resourceUrl(it).toString(), false)
        }
        val indexed = LibraryIndexer.index(files, sourceId, generation, work.entity())
        require(indexed.tracks.isNotEmpty()) { "作品暂无可播放音轨" }
        return indexed.copy(
            attachments =
                indexed.attachments.map {
                    if (it.relativePath == "_asmr-cover/main.jpg") it.copy(kind = "cover") else it
                },
            tracks = indexed.tracks.map { it.copy(durationMs = durations[it.relativePath] ?: 0) },
        )
    }

    suspend fun bytes(url: String, limit: Int): ByteArray =
        withContext(Dispatchers.IO) {
            http.newCall(request(resourceUrl(url))).execute().use { response ->
                checkStatus(response)
                boundedRead(response.body.byteStream(), limit)
            }
        }

    private suspend fun json(url: HttpUrl): JsonElement =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request(url))
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive)
                            continuation.resumeWithException(IOException("ASMR.ONE 连接失败", e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                checkStatus(it)
                                Json.parseToJsonElement(
                                    boundedRead(it.body.byteStream(), 4 * 1024 * 1024)
                                        .toString(Charsets.UTF_8),
                                )
                            }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            if (continuation.isActive)
                                continuation.resumeWithException(
                                    if (e is IOException) e else IOException("ASMR.ONE 响应格式错误", e),
                                )
                        }
                    }
                },
            )
        }

    private fun resourceUrl(value: String): HttpUrl {
        val url = value.toHttpUrlOrNull() ?: throw IOException("文件地址无效")
        val localTest =
            root.host in setOf("localhost", "127.0.0.1", "10.0.2.2") && url.host == root.host
        if (
            !((url.scheme == "https" || localTest) &&
                url.username.isEmpty() &&
                url.password.isEmpty())
        )
            throw IOException("文件地址须使用 HTTPS")
        return url
    }

    private fun request(url: HttpUrl) =
        Request.Builder()
            .url(url)
            .apply { MEDIA_HEADERS.forEach { (k, v) -> header(k, v) } }
            .build()

    private fun checkStatus(response: Response) {
        if (!response.isSuccessful) throw IOException("ASMR.ONE 请求失败（${response.code}）")
    }

    private fun parseWork(body: JsonObject): AsmrWork {
        val id = body["id"]?.jsonPrimitive?.longOrNull ?: throw IOException("作品编号无效")
        require(id > 0) { "作品编号无效" }
        val sourceId = body.text("source_id").uppercase()
        val rj =
            sourceId.takeIf { it.startsWith("RJ") && it.drop(2).toLongOrNull() == id }
                ?: "RJ" + id.toString().padStart(if (id >= 1000000) 8 else 6, '0')
        val voices =
            (body["vas"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonObject)?.text("name")?.takeIf(String::isNotBlank)
            }
        val artist =
            voices.joinToString(" / ").ifBlank {
                (body["circle"] as? JsonObject)?.text("name").orEmpty()
            }
        val cover =
            body
                .text("mainCoverUrl")
                .ifBlank { body.text("samCoverUrl") }
                .takeIf(String::isNotBlank)
                ?.let { resourceUrl(it).toString() }
        return AsmrWork(id, body.text("title").ifBlank { rj }, rj, artist, cover)
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
