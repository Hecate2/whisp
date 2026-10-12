// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.DlsiteClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DlsiteLiveIntegrationTest {
    @Test
    fun officialMetadataIsReadableOnTheDevice(): Unit = runBlocking {
        val rj = InstrumentationRegistry.getArguments().getString("probeRj")
        assumeNotNull(rj)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".smoketest"))
        val repo = (context.applicationContext as WhispApp).repository
        val requests = CopyOnWriteArrayList<String>()
        val http =
            repo.http
                .newBuilder()
                .followRedirects(true)
                .addInterceptor { chain ->
                    val phase =
                        if (chain.request().url.encodedPath.endsWith("ajax")) "info" else "page"
                    try {
                        val response = chain.proceed(chain.request())
                        requests.add("$phase:HTTP ${response.code}")
                        response
                    } catch (e: Exception) {
                        requests.add("$phase:${e.javaClass.simpleName}")
                        throw e
                    }
                }
                .build()
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            val metadata = DlsiteClient(http).work(rj!!)
            assertTrue(metadata.title.isNotBlank())
            println(
                "DLSITE_PROBE success; elapsed=${android.os.SystemClock.elapsedRealtime() - started}; requests=$requests",
            )
        } catch (e: Exception) {
            val causes =
                generateSequence<Throwable>(e) { it.cause }
                    .take(8)
                    .map { it.javaClass.simpleName }
                    .joinToString(" | ")
            println("DLSITE_PROBE failed; requests=$requests; causes=$causes")
            throw AssertionError("DLsite probe failed: $requests; $causes")
        }
    }
}
