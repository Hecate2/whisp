// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.parser.Parser

data class DlsiteMetadata(
    val rj: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val series: String?,
) {
    val coverPath
        get() = "_dlsite-cover/${stableId(coverUrl)}.jpg"

    fun applyTo(work: AsmrWork) =
        work.copy(
            title = title,
            artist = artist,
            coverUrl = coverUrl,
            coverPath = coverPath,
            series = series,
        )
}

class DlsiteClient(
    private val http: OkHttpClient,
    private val root: HttpUrl = "https://www.dlsite.com/".toHttpUrl(),
) {
    suspend fun work(number: String): DlsiteMetadata {
        val rj = number.uppercase()
        require(Regex("RJ[0-9]{6,8}").matches(rj)) { "RJ 编号无效" }
        val full =
            root
                .newBuilder()
                .addPathSegments("maniax/api/=/product.json")
                .addQueryParameter("workno", rj)
                .addQueryParameter("locale", "ja_JP")
                .build()
        try {
            val data = read(full)
            return withContext(Dispatchers.Default) { parseProduct(rj, data) }
        } catch (e: CancellationException) {
            throw e
        } catch (primary: Exception) {
            val fallback =
                root
                    .newBuilder()
                    .addPathSegments("maniax/product/info/ajax")
                    .addQueryParameter("product_id", rj)
                    .addQueryParameter("locale", "ja_JP")
                    .build()
            try {
                val data = read(fallback)
                return withContext(Dispatchers.Default) { parseInfo(rj, data) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.addSuppressed(primary)
                val message =
                    when (e) {
                        is OnlineHttpException -> "DLsite 连接失败（${e.code}）"
                        is java.net.SocketTimeoutException -> "DLsite 连接超时"
                        else -> e.message?.takeIf { it.startsWith("DLsite ") } ?: "DLsite 元数据读取失败"
                    }
                throw IOException(message, e)
            }
        }
    }

    private suspend fun read(url: HttpUrl) =
        http
            .onlineBytes(
                Request.Builder().url(url).header("Accept-Language", "ja-JP").build(),
                4 * 1024 * 1024,
            )
            .toString(Charsets.UTF_8)

    internal fun parseProduct(rj: String, data: String): DlsiteMetadata {
        val items =
            Json.parseToJsonElement(data) as? JsonArray ?: throw IOException("DLsite 作品数据格式错误")
        val item =
            items
                .mapNotNull { it as? JsonObject }
                .firstOrNull {
                    it.text("product_id") == rj || it.text("workno") == rj
                } ?: throw IOException("DLsite 未找到该作品")
        require(item.text("product_id").ifBlank { item.text("workno") } == rj) {
            "DLsite 作品编号不匹配"
        }
        return metadata(rj, item, (item["image_main"] as? JsonObject)?.text("url").orEmpty())
    }

    internal fun parseInfo(rj: String, data: String): DlsiteMetadata {
        val item =
            (Json.parseToJsonElement(data) as? JsonObject)?.get(rj) as? JsonObject
                ?: throw IOException("DLsite 未找到该作品")
        // The official keyed response can omit product_id; an explicit conflicting ID is invalid.
        require(item.text("product_id").ifBlank { rj } == rj) { "DLsite 作品编号不匹配" }
        return metadata(rj, item, item.text("work_image"))
    }

    private fun metadata(rj: String, item: JsonObject, image: String): DlsiteMetadata {
        val title = Parser.unescapeEntities(item.text("work_name"), false).trim()
        require(title.isNotBlank()) { "DLsite 作品名为空" }
        val creators = item["creaters"] as? JsonObject
        val voices =
            (creators?.get("voice_by") as? JsonArray)
                .orEmpty()
                .mapNotNull { (it as? JsonObject)?.text("name")?.takeIf(String::isNotBlank) }
                .distinct()
        val artist = voices.joinToString(" / ").ifBlank { item.text("maker_name") }
        val cover =
            (if (image.startsWith("//")) "https:$image" else image).toHttpUrlOrNull()
                ?: throw IOException("DLsite 封面地址无效")
        val localTest =
            root.host in setOf("localhost", "127.0.0.1", "10.0.2.2") && cover.host == root.host
        require(
            (cover.scheme == "https" || localTest) &&
                cover.username.isEmpty() &&
                cover.password.isEmpty(),
        ) {
            "DLsite 封面地址无效"
        }
        require(
            localTest ||
                listOf("dlsite.com", "dlsite.jp").any {
                    cover.host == it || cover.host.endsWith(".$it")
                },
        ) {
            "DLsite 封面来源无效"
        }
        return DlsiteMetadata(
            rj,
            title,
            artist,
            cover.toString(),
            item.text("title_name").ifBlank { item.text("series_name") }.takeIf(String::isNotBlank),
        )
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
