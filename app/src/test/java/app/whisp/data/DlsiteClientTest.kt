// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import mockwebserver3.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class DlsiteClientTest {
    private val info =
        """{"RJ00000001":{"product_id":"RJ00000001","work_name":"官方示例 &amp; 标题","maker_name":"示例社团","work_image":"//img.dlsite.jp/example.jpg","title_name":"示例系列"}}"""
    private val page =
        """<h1 id="work_name">官方示例</h1><table id="work_outline"><tr><th>声優</th><td><a>示例甲</a> / <a>示例乙</a><a>示例甲</a></td></tr></table>"""

    @Test
    fun officialFieldsReplaceCatalogMetadataWithoutChangingAudioIdentity() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) =
                        MockResponse.Builder()
                            .body(if (request.url.encodedPath.endsWith("/ajax")) info else page)
                            .build()
                }
            val metadata = DlsiteClient(OkHttpClient(), server.url("/")).work("rj00000001")
            assertEquals("官方示例 & 标题", metadata.title)
            assertEquals("示例甲 / 示例乙", metadata.artist)
            assertEquals("https://img.dlsite.jp/example.jpg", metadata.coverUrl)
            assertEquals("示例系列", metadata.series)
            val old = AsmrWork(1, "目录标题", "RJ00000001", "目录声优", "https://media.example/cover.jpg")
            val new = metadata.applyTo(old)
            assertEquals(old.workId, new.workId)
            assertNotEquals(old.coverId, new.coverId)
            assertEquals(metadata.artist, new.entity().originalArtist)
            assertTrue(isAsmrOneWork(new.entity()))
            val requests = listOf(server.takeRequest(), server.takeRequest())
            assertTrue(
                requests.any {
                    it.url.encodedPath == "/maniax/product/info/ajax" &&
                        it.url.queryParameter("product_id") == "RJ00000001"
                },
            )
            assertTrue(
                requests.any { it.url.encodedPath == "/maniax/work/=/product_id/RJ00000001.html" },
            )
            requests.forEach {
                assertEquals("ja_JP", it.url.queryParameter("locale"))
                assertEquals("ja-JP", it.headers["Accept-Language"])
                assertNull(it.headers["Authorization"])
                assertNull(it.headers["Origin"])
            }
        }
    }

    @Test
    fun circleIsTheOfficialFallbackAndAnAgeGateIsRejected() {
        val client = DlsiteClient(OkHttpClient())
        val result = client.parse("RJ00000001", info, "<h1 id='work_name'>示例</h1>")
        assertEquals("示例社团", result.artist)
        assertThrows(IllegalArgumentException::class.java) {
            client.parse("RJ00000001", info, "<html>入口</html>")
        }
    }

    @Test
    fun mismatchedIdentifiersAndUntrustedCoversAreRejected() {
        val client = DlsiteClient(OkHttpClient())
        assertThrows(IllegalArgumentException::class.java) {
            client.parse(
                "RJ00000001",
                info.replace("\"product_id\":\"RJ00000001\"", "\"product_id\":\"RJ00000002\""),
                page,
            )
        }
        for (url in
            listOf(
                "http://img.dlsite.jp/cover.jpg",
                "https://listener:test@img.dlsite.jp/cover.jpg",
                "https://media.example/cover.jpg",
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                client.parse("RJ00000001", info.replace("//img.dlsite.jp/example.jpg", url), page)
            }
        }
        assertThrows(IOException::class.java) { client.parse("RJ00000001", "{}", page) }
    }

    @Test
    fun cancellationStopsBothOfficialRequests() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(2) {
                server.enqueue(
                    MockResponse.Builder().body(info).bodyDelay(3, TimeUnit.SECONDS).build(),
                )
            }
            val job =
                launch(Dispatchers.Default) {
                    DlsiteClient(OkHttpClient(), server.url("/")).work("RJ00000001")
                }
            repeat(2) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }
}
