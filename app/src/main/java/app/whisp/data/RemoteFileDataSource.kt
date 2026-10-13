// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException

@androidx.annotation.OptIn(UnstableApi::class)
class RemoteFileDataSource(
    private val protocol: String = "网络",
    private val readerFor: (String) -> RemoteReadHandle,
) : BaseDataSource(true) {
    private var reader: RemoteReadHandle? = null
    private var spec: DataSpec? = null
    private var position = 0L
    private var remaining = 0L
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            reader = readerFor(dataSpec.uri.toString())
            if (dataSpec.position > reader!!.length)
                throw DataSourceException(
                    "播放位置超出文件长度",
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                )
            spec = dataSpec
            position = dataSpec.position
            remaining = reader!!.length - position
            if (dataSpec.length != C.LENGTH_UNSET.toLong())
                remaining = minOf(remaining, dataSpec.length)
            started = true
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) {
            close()
            throw if (e is IOException) e else IOException(e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count =
            reader!!.read(buffer, position, offset, minOf(length.toLong(), remaining).toInt())
        if (count <= 0) throw IOException("$protocol 文件读取中断")
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = spec?.uri

    override fun close() {
        try {
            reader?.close()
        } finally {
            reader = null
            spec = null
            if (started) {
                started = false
                transferEnded()
            }
        }
    }
}
