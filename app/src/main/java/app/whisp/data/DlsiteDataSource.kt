// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

data class DlsiteMediaResource(
    val client: DlsiteAccountClient,
    val workno: String,
    val path: String,
)

@androidx.annotation.OptIn(UnstableApi::class)
class DlsiteDataSource(
    private val resolve: suspend (String, String) -> DlsiteMediaResource,
    private val loginRequired: (String) -> Unit = {},
) : DataSource {
    private var active: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(listener: TransferListener) {
        listeners += listener
    }

    override fun open(spec: DataSpec): Long {
        return try {
            openAuthorized(spec)
        } catch (e: DlsiteLoginRequired) {
            spec.uri.authority?.let(loginRequired)
            throw e
        }
    }

    private fun openAuthorized(spec: DataSpec): Long {
        val source = spec.uri.authority ?: throw IOException("来源未关联")
        val asset = spec.uri.lastPathSegment ?: throw IOException("文件标识无效")
        val resource = runBlocking(Dispatchers.IO) { resolve(source, asset) }
        for (attempt in 0..1) {
            val url =
                runBlocking(Dispatchers.IO) {
                    resource.client.signedUrl(resource.workno, resource.path, force = attempt > 0)
                }
            val upstream =
                OkHttpDataSource.Factory(resource.client.http)
                    .setDefaultRequestProperties(mapOf("Referer" to "https://play.dlsite.com/"))
                    .createDataSource()
            listeners.forEach(upstream::addTransferListener)
            active = upstream
            try {
                return upstream.open(spec.withUri(url.toString().toUri()))
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                upstream.close()
                active = null
                if (e.responseCode !in setOf(401, 403))
                    throw IOException("DLsite 音频读取失败（${e.responseCode}）")
                if (attempt == 1) throw DlsiteLoginRequired()
            } catch (e: Exception) {
                upstream.close()
                active = null
                throw IOException("DLsite 音频连接失败", e)
            }
        }
        throw DlsiteLoginRequired()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int) =
        active?.read(buffer, offset, length) ?: throw IOException("音频尚未打开")

    override fun getUri() = active?.uri

    override fun getResponseHeaders() = active?.responseHeaders ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }
}
