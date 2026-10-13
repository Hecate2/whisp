// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DlsiteDataSourceTest {
    @Before
    fun initializeHttp() {
        OkHttp.initialize(RuntimeEnvironment.getApplication())
    }

    private fun source(server: MockWebServer) =
        DlsiteDataSource(
            resolve = { _, _ ->
                DlsiteMediaResource(
                    DlsiteAccountClient(
                        DlsiteSession(),
                        root = server.url("/"),
                        contentRoot = server.url("/"),
                        testTransport = true,
                    ),
                    "RJ00000001",
                    "optimized/example.mp3",
                )
            },
        )

    private fun signature() =
        MockResponse.Builder().body("""{"expires":"2099-01-01T00:00:00Z"}""").build()

    @Test
    fun authorizationRefreshPreservesRangeAndRequestHeaders() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(signature())
            server.enqueue(MockResponse.Builder().code(403).build())
            server.enqueue(signature())
            server.enqueue(
                MockResponse.Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes 2-4/6")
                    .body("cde")
                    .build(),
            )
            val data = source(server)
            try {
                assertEquals(
                    3L,
                    data.open(
                        DataSpec.Builder()
                            .setUri("whisp-dlsite://account/asset")
                            .setPosition(2)
                            .setLength(3)
                            .setKey("asset-cache-key")
                            .setHttpRequestHeaders(mapOf("X-Example" to "range"))
                            .build(),
                    ),
                )
                val buffer = ByteArray(10)
                assertEquals(3, data.read(buffer, 0, buffer.size))
                assertEquals("cde", buffer.copyOf(3).toString(Charsets.UTF_8))
                assertEquals(C.RESULT_END_OF_INPUT, data.read(buffer, 0, buffer.size))
                server.takeRequest()
                val initial = server.takeRequest()
                server.takeRequest()
                val refreshed = server.takeRequest()
                for (request in listOf(initial, refreshed)) {
                    assertEquals("bytes=2-4", request.headers["Range"])
                    assertEquals("range", request.headers["X-Example"])
                }
            } finally {
                data.close()
            }
        }
    }

    @Test
    fun beyondEndRetainsTheMedia3PositionErrorAndExactEndIsEof() {
        MockWebServer().use { server ->
            server.start()
            for (position in listOf(7L, 6L)) {
                server.enqueue(signature())
                server.enqueue(
                    MockResponse.Builder()
                        .code(416)
                        .addHeader("Content-Range", "bytes */6")
                        .build(),
                )
                val data = source(server)
                try {
                    val spec =
                        DataSpec.Builder()
                            .setUri("whisp-dlsite://account/asset")
                            .setPosition(position)
                            .build()
                    if (position > 6) {
                        try {
                            data.open(spec)
                            fail("Out of range position accepted")
                        } catch (e: IOException) {
                            assertTrue(DataSourceException.isCausedByPositionOutOfRange(e))
                        }
                    } else {
                        assertEquals(0L, data.open(spec))
                        assertEquals(C.RESULT_END_OF_INPUT, data.read(ByteArray(1), 0, 1))
                    }
                } finally {
                    data.close()
                }
            }
        }
    }
}
