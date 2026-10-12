// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import org.junit.Assert.*
import org.junit.Test

class Mp3MetadataTest {
    @Test
    fun readsIndexedDurationAndSkipsId3() {
        val data = ByteArray(500)
        "ID3".toByteArray().copyInto(data)
        data[9] = 20
        val offset = 30
        byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0).copyInto(data, offset)
        "Info".toByteArray().copyInto(data, offset + 36)
        data[offset + 43] = 1 // frame-count flag
        data[offset + 44] = 0
        data[offset + 45] = 0
        data[offset + 46] = 0x03
        data[offset + 47] = 0xe8.toByte()
        val reads = mutableListOf<Long>()
        val duration =
            Mp3Metadata.duration(data.size.toLong()) { start, length ->
                reads += start
                data.copyOfRange(start.toInt(), minOf(data.size, start.toInt() + length))
            }
        assertEquals(1000L * 1152 * 1000 / 44100, duration)
        assertEquals(listOf(0L, 30L), reads)
    }

    @Test
    fun doesNotGuessDurationWithoutAnIndex() {
        val data = ByteArray(100)
        byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0).copyInto(data)
        assertEquals(
            0L,
            Mp3Metadata.duration(100) { start, length ->
                data.copyOfRange(start.toInt(), minOf(100, start.toInt() + length))
            },
        )
    }
}
