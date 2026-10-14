// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class SmbDataSourceTest {
    private class Handle(override val length: Long) : RemoteReadHandle {
        val offsets = mutableListOf<Long>()
        var closes = 0

        override fun read(buffer: ByteArray, offset: Long, bufferOffset: Int, count: Int): Int {
            offsets += offset
            for (i in 0 until count) buffer[bufferOffset + i] = (offset + i).toByte()
            return count
        }

        override fun close() {
            closes++
        }
    }

    @Test
    fun boundedReadRetainsOffsetsAboveFourGiBAndClosesItsHandle() {
        val handle = Handle(5L * 1024 * 1024 * 1024)
        val data = RemoteFileDataSource { handle }
        val position = 4_294_967_303L
        val spec =
            DataSpec.Builder()
                .setUri("whisp-smb://source/example.mp3")
                .setPosition(position)
                .setLength(5)
                .build()
        try {
            assertEquals(5L, data.open(spec))
            val bytes = ByteArray(9)
            assertEquals(5, data.read(bytes, 2, 7))
            assertArrayEquals(byteArrayOf(7, 8, 9, 10, 11), bytes.copyOfRange(2, 7))
            assertEquals(listOf(position), handle.offsets)
            assertEquals(C.RESULT_END_OF_INPUT, data.read(bytes, 0, bytes.size))
            assertEquals(spec.uri, data.getUri())
        } finally {
            data.close()
        }
        assertEquals(1, handle.closes)
        assertNull(data.getUri())
    }

    @Test
    fun invalidPositionHasTheMedia3ReasonAndReleasesTheOpenedHandle() {
        val handles = mutableListOf<Handle>()
        val data = RemoteFileDataSource { Handle(10).also(handles::add) }
        try {
            data.open(
                DataSpec.Builder().setUri("whisp-smb://source/example.mp3").setPosition(11).build(),
            )
            fail("Out of range position accepted")
        } catch (e: IOException) {
            assertTrue(DataSourceException.isCausedByPositionOutOfRange(e))
        }
        assertEquals(1, handles.single().closes)
        assertNull(data.getUri())
        try {
            assertEquals(
                0L,
                data.open(
                    DataSpec.Builder()
                        .setUri("whisp-smb://source/example.mp3")
                        .setPosition(10)
                        .build(),
                ),
            )
            assertEquals(C.RESULT_END_OF_INPUT, data.read(ByteArray(1), 0, 1))
        } finally {
            data.close()
        }
        assertEquals(listOf(1, 1), handles.map { it.closes })
    }
}
