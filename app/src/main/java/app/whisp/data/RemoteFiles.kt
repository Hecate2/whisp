// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.Closeable

interface RemoteReadHandle : Closeable {
    val length: Long

    fun read(buffer: ByteArray, offset: Long, bufferOffset: Int, count: Int): Int
}

interface RemoteAssetSession : Closeable {
    fun bytes(uri: String, limit: Int): ByteArray

    fun duration(uri: String): Long
}
