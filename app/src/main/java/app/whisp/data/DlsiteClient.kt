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
import org.jsoup.Jsoup
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
    suspend fun work(number: String): DlsiteMetadata = coroutineScope {
        val rj = number.uppercase()
        require(Regex("RJ[0-9]{6,8}").matches(rj)) { "RJ 编号无效" }
        val infoUrl =
            root
                .newBuilder()
                .addPathSegments("maniax/product/info/ajax")
                .addQueryParameter("product_id", rj)
                .addQueryParameter("locale", "ja_JP")
                .build()
        val pageUrl =
            root
                .newBuilder()
                .addPathSegments("maniax/work/=/product_id")
                .addPathSegment("$rj.html")
                .addQueryParameter("locale", "ja_JP")
                .build()
        val info = async { read(infoUrl) }
        val page = async { read(pageUrl) }
        try {
            withContext(Dispatchers.Default) { parse(rj, info.await(), page.await()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("DLsite 元数据读取失败", e)
        }
    }

    private suspend fun read(url: HttpUrl) =
        http
            .onlineBytes(
                Request.Builder().url(url).header("Accept-Language", "ja-JP").build(),
                4 * 1024 * 1024,
            )
            .toString(Charsets.UTF_8)

    internal fun parse(rj: String, info: String, page: String): DlsiteMetadata {
        val item =
            Json.parseToJsonElement(info).jsonObject[rj]?.jsonObject
                ?: throw IOException("DLsite 未找到该作品")
        require(item.text("product_id") == rj) { "DLsite 作品编号不匹配" }
        val document = Jsoup.parse(page, root.toString())
        require(document.selectFirst("#work_name") != null) { "DLsite 作品页不可用" }
        val title = Parser.unescapeEntities(item.text("work_name"), false).trim()
        require(title.isNotBlank()) { "DLsite 作品名为空" }
        val voices =
            document
                .select("#work_outline tr")
                .firstOrNull { it.selectFirst("th")?.text() == "声優" }
                ?.selectFirst("td")
        val artist =
            voices
                ?.select("a")
                ?.map { it.text().trim() }
                ?.filter(String::isNotBlank)
                ?.distinct()
                ?.joinToString(" / ")
                .orEmpty()
                .ifBlank { voices?.text().orEmpty() }
                .ifBlank { item.text("maker_name") }
        val image =
            item.text("work_image").ifBlank {
                document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            }
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
            item.text("title_name").takeIf(String::isNotBlank),
        )
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
