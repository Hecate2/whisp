// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AsmrOneClientTest {
    private val work =
        """{"id":1,"source_id":"RJ00000001","title":"示例作品","vas":[{"name":"示例声优"}],"mainCoverUrl":"https://media.example/cover.jpg"}"""

    private fun response(body: String) = MockResponse.Builder().body(body).build()

    @Test
    fun sharedUrlsKeepEachAssetsLocationAcrossWorks() {
        val result =
            LibraryIndexer.index(
                listOf(
                    FileEntry(
                        "RJ00000001/01_示例.wav",
                        "https://media.example/shared.wav",
                        false,
                        100,
                    ),
                    FileEntry(
                        "RJ00000001/02_示例.wav",
                        "https://media.example/shared.wav",
                        false,
                        100,
                    ),
                    FileEntry(
                        "RJ00000002/01_示例.wav",
                        "https://media.example/shared.wav",
                        false,
                        100,
                    ),
                ),
                "source",
                1,
            )
        assertEquals(3, result.locations.map { it.id }.distinct().size)
        assertEquals(
            result.tracks.map { it.id }.toSet(),
            result.locations.map { it.assetId }.toSet(),
        )
    }

    @Test
    fun catalogUsesEncodedSearchFixedPagesFiltersAndPublicHeaders() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response("""{"works":[$work],"pagination":{"totalCount":41}}"""))
            val client = AsmrOneClient(okhttp3.OkHttpClient(), server.url("/"))
            val page = client.catalog(2, "示例/声优", "dl_count", true)
            val request = server.takeRequest()
            assertEquals("示例/声优", request.url.pathSegments.last())
            assertTrue(request.url.encodedPath.contains("%2F"))
            assertEquals("2", request.url.queryParameter("page"))
            assertEquals("20", request.url.queryParameter("pageSize"))
            assertEquals("dl_count", request.url.queryParameter("order"))
            assertEquals("1", request.url.queryParameter("subtitle"))
            assertEquals("https://asmr.one/", request.headers["Referer"])
            assertNull(request.headers["Authorization"])
            assertTrue(page.hasMore)
            assertEquals("RJ00000001", page.works.single().rj)
            assertEquals("示例声优", page.works.single().artist)
        }
    }

    @Test
    fun metadataUsesCaseSensitiveEndpointAndCanonicalEightDigitCode() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                response("""{"id":1000001,"title":"示例作品","circle":{"name":"示例社团"},"vas":null}"""),
            )
            val result = AsmrOneClient(okhttp3.OkHttpClient(), server.url("/")).work(1000001)
            assertEquals("/api/workInfo/1000001", server.takeRequest().url.encodedPath)
            assertEquals("RJ01000001", result.rj)
            assertEquals("示例社团", result.artist)
            assertNull(result.coverId)
        }
    }

    @Test
    fun indexingPreservesVariantsSubtitlesBonusDurationsAndStableIdentity() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val tree =
                """[
                {"type":"folder","title":"MP3","children":[
                    {"type":"audio","title":"01_示例.mp3","mediaStreamUrl":"https://media.example/a?revision=1","duration":1.234,"size":100},
                    {"type":"text","title":"01_示例.mp3.vtt","mediaStreamUrl":"https://media.example/sub.vtt"}]},
                {"type":"folder","title":"WAV","children":[{"type":"audio","title":"01_示例.wav","mediaStreamUrl":"https://media.example/b","duration":2,"size":200}]},
                {"type":"folder","title":"特典 RJ00000002","children":[{"type":"audio","title":"02_附赠.mp3","mediaStreamUrl":"https://media.example/c","size":300}]}
            ]"""
            server.enqueue(response(tree))
            server.enqueue(response(tree.replace("revision=1", "revision=2")))
            val client = AsmrOneClient(okhttp3.OkHttpClient(), server.url("/"))
            val info = AsmrWork(1, "示例作品", "RJ00000001", "示例声优", "https://media.example/cover.jpg")
            val first = client.index(info, "source", 1)
            val refreshed = client.index(info.copy(title = "更新标题"), "source", 2)
            assertEquals(1, first.works.size)
            assertEquals(info.workId, first.works.single().id)
            assertEquals(3, first.tracks.size)
            val mp3 = first.tracks.first { it.relativePath == "MP3/01_示例.mp3" }
            assertEquals(1234L, mp3.durationMs)
            assertNotNull(mp3.subtitleId)
            assertTrue(first.tracks.single { it.relativePath.startsWith("特典") }.bonus)
            assertEquals(first.tracks.map { it.id }, refreshed.tracks.map { it.id })
            assertEquals(info.coverId, first.works.single().coverId)
            assertEquals(
                info.coverId,
                first.attachments.single { it.relativePath == "_asmr-cover/main.jpg" }.id,
            )
            assertTrue(refreshed.locations.any { it.uri.endsWith("revision=2") })
        }
    }

    @Test
    fun resourceUrlsRequireHttpsAndSeparateCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (url in
                listOf(
                    "http://media.example/cover.jpg",
                    "https://listener:test@media.example/cover.jpg",
                )) {
                server.enqueue(response(work.replace("https://media.example/cover.jpg", url)))
                try {
                    AsmrOneClient(okhttp3.OkHttpClient(), server.url("/")).work(1)
                    fail("Unsafe media URL accepted")
                } catch (_: IOException) {}
            }
        }
    }

    @Test
    fun httpErrorsRemainRetryableAndEmptyFinalPagesStopPagination() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
            server.enqueue(response("""{"works":[],"pagination":{"totalCount":40}}"""))
            val client = AsmrOneClient(okhttp3.OkHttpClient(), server.url("/"))
            val source = AsmrPagingSource { client.catalog(it) }
            val params = PagingSource.LoadParams.Refresh<Int>(null, 20, false)
            assertTrue(source.load(params) is PagingSource.LoadResult.Error)
            val page = source.load(params) as PagingSource.LoadResult.Page
            assertNull(page.nextKey)
            assertTrue(page.data.isEmpty())
        }
    }

    @Test
    fun pagingKeysRefreshAtTheVisiblePage() = runBlocking {
        val info = AsmrWork(1, "示例作品", "RJ00000001", "", null)
        val source = AsmrPagingSource { AsmrPage(listOf(info), it < 3) }
        val page =
            source.load(PagingSource.LoadParams.Refresh(2, 20, false))
                as PagingSource.LoadResult.Page
        assertEquals(1, page.prevKey)
        assertEquals(3, page.nextKey)
        assertEquals(2, source.getRefreshKey(PagingState(listOf(page), 0, PagingConfig(20), 0)))
    }

    @Test
    fun cancellationStopsTheNetworkRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .body("""{"works":[]}""")
                    .bodyDelay(3, TimeUnit.SECONDS)
                    .build(),
            )
            val job =
                launch(Dispatchers.Default) {
                    AsmrOneClient(okhttp3.OkHttpClient(), server.url("/")).catalog(1)
                }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }
}
