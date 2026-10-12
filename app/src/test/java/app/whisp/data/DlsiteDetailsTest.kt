// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class DlsiteDetailsTest {
    private val client = DlsiteClient(OkHttpClient())
    private val product =
        """[{"product_id":"RJ00000001","work_name":"示例作品","maker_name":"示例社团","maker_id":"circle","regist_date":"2026-01-02 00:00:00","work_type_string":"音声","age_category_string":"成人","file_type_string":"WAV","file_type_special":"MP3","contents_file_size":5000,"creaters":{"voice_by":[{"id":"va","name":"示例声优"}],"scenario_by":[{"name":"示例剧本"}],"illust_by":[{"name":"示例插画"}]},"genres":[{"id":1,"name":"示例标签"}],"custom_genres":[{"name":"目录词典"}],"series_id":"series","series_name":"示例系列","editions":[{"workno":"RJ00000001"},{"workno":"RJ00000002","label":"系列第二部","lang":"JPN,ENG"}],"translation_info":{"is_parent":true,"is_original":false,"original_workno":"RJ00000003","lang":"CHI_HANS","child_worknos":["RJ00000004"]},"language_editions":[{"workno":"RJ00000005","label":"另一译本","lang":"ENG"}],"intro_s":"<p>示例 &amp; 摘要</p>","image_main":{"url":"//img.dlsite.jp/cover.jpg"},"image_samples":[{"url":"//img.dlsite.jp/sample.jpg"},{"url":"https://unknown.example/untrusted.jpg"}]}]"""

    @Test
    fun officialRolesTagsAndTranslationRelationsRemainDistinct() {
        val details = client.parseProduct("RJ00000001", product).details
        assertEquals("示例社团", details.circle)
        assertEquals("2026-01-02", details.released)
        assertEquals(listOf("WAV", "MP3"), details.formats)
        assertEquals(3, details.credits.size)
        assertEquals("剧本", details.credits.first { it.role == "scenario_by" }.label)
        assertEquals(listOf("示例标签"), details.genres.map { it.name })
        assertEquals(listOf("RJ00000002"), details.seriesWorks.map { it.rj })
        assertEquals(
            setOf("RJ00000004", "RJ00000005"),
            details.languageEditions.map { it.rj }.toSet(),
        )
        assertEquals("RJ00000003", details.originalRj)
        assertNull(details.translationParentRj)
        assertTrue(details.translated)
        assertEquals("简体中文", dlsiteLanguage(details.translationLanguage!!))
        assertEquals("示例 & 摘要", details.summary)
        assertEquals(1, details.images.size)
        assertEquals(details, decodeDlsiteDetails(details.encode()))
    }

    @Test
    fun malformedOptionalFieldsDoNotBlockTheWork() {
        val changed =
            product
                .replace("\"genres\":[{\"id\":1,\"name\":\"示例标签\"}]", "\"genres\":false")
                .replace("\"contents_file_size\":5000", "\"contents_file_size\":-1")
                .replace("2026-01-02", "bad-date")
        val details = client.parseProduct("RJ00000001", changed).details
        assertTrue(details.genres.isEmpty())
        assertNull(details.packageBytes)
        assertNull(details.released)
        assertEquals(3, details.credits.size)
        val previous = client.parseProduct("RJ00000001", product).details.copy(productFetchedAt = 1)
        assertEquals(previous.genres, details.preserving(previous).genres)
    }

    @Test
    fun exactRatingUsesTheStatisticsEndpointAndNeverStarPresentation() {
        val stats =
            client.parseStatistics(
                "RJ00000001",
                """{"RJ00000001":{"rate_average_2dp":"4.88","rate_average_star":50,"rate_count":185,"dl_count":100,"price":900}}""",
            )
        assertEquals(4.88, stats.rating!!, .001)
        assertEquals(185L, stats.ratings)
        assertNull(
            client
                .parseStatistics("RJ00000001", """{"RJ00000001":{"rate_average_star":50}}""")
                .rating,
        )
        assertThrows(IOException::class.java) { client.parseStatistics("RJ00000001", "{}") }
    }

    @Test
    fun introductionKeepsReadingOrderAndIgnoresExecutableOrUntrustedContent() {
        val page =
            """<h1 id='work_name'>示例</h1><div class='work_parts_container'><h3 class='work_parts_heading'>介绍标题</h3><p>第一段<br>第二行</p><script>private-script</script><img src='//img.dlsite.jp/example.jpg'><p>重复段落</p><p>重复段落</p><img src='https://unknown.example/image.jpg'><iframe>hidden</iframe></div>"""
        val blocks = client.parseDescription("RJ00000001", page)
        assertEquals(listOf("heading", "text", "image", "text", "text"), blocks.map { it.kind })
        assertEquals("第一段\n第二行", blocks[1].text)
        assertEquals(2, blocks.count { it.text == "重复段落" })
        assertFalse(blocks.any { it.text.contains("private-script") })
        assertThrows(IllegalArgumentException::class.java) {
            client.parseDescription("RJ00000001", "<html>入口</html>")
        }
    }

    @Test
    fun fallbackKeepsKnownOfficialVoicesInTheLibraryAndPreview() {
        val previous = client.parseProduct("RJ00000001", product).details
        val fallback =
            DlsiteMetadata("RJ00000001", "更新标题", "示例社团", "https://img.dlsite.jp/cover.jpg", null)
                .withPrevious(previous)
        assertEquals("示例声优", fallback.artist)
        assertEquals(previous.credits, fallback.details.credits)
        assertEquals("更新标题", fallback.title)
    }

    @Test
    fun lateRequestsAndKeyedFallbackPreserveNewerDetails() {
        val old =
            client
                .parseProduct("RJ00000001", product)
                .details
                .copy(
                    productFetchedAt = 1,
                    description = listOf(DlsiteBlock("text", "已缓存介绍")),
                    descriptionFetchedAt = 10,
                    statistics = DlsiteStatistics(rating = 4.8, fetchedAt = 10),
                )
        val late = old.copy(description = emptyList(), descriptionFetchedAt = 0, statistics = null)
        assertEquals(old, late.preserving(old))
        val fallback = DlsiteDetails("RJ00000001", circle = "更新社团")
        val merged = fallback.preserving(old)
        assertEquals(old.credits, merged.credits)
        assertEquals(old.description, merged.description)
        assertEquals("更新社团", merged.circle)
        assertEquals(old, old.copy(productFetchedAt = 0).preserving(old))
    }
}
