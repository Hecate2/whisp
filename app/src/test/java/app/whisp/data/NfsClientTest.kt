// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NfsClientTest {
    private val source = SourceEntity("nfs-test", "示例来源", "nfs", "nfs://nas.example/media")

    private class Session(val tree: Map<String, List<NfsEntry>>, val broken: String? = null) :
        NfsSession {
        var closed = false
        val listed = CopyOnWriteArrayList<String>()

        override fun list(path: String): List<NfsEntry> {
            listed += path
            if (path == broken) throw IOException("synthetic failure")
            return tree[path].orEmpty()
        }

        override fun open(path: String): RemoteReadHandle = error("unexpected read")

        override fun close() {
            closed = true
        }
    }

    @Test
    fun traversalUsesDirectoryAttributesAndClosesEveryConnection() = runBlocking {
        val tree =
            mapOf(
                "" to (1..5).map { NfsEntry("目录$it", 2, 0, 0) } + NfsEntry("循环链接", 5, 0, 0),
                *(1..5)
                    .map { "目录$it" to listOf(NfsEntry("01. 示例😀.wav", 1, 123, 456)) }
                    .toTypedArray(),
            )
        val sessions = CopyOnWriteArrayList<Session>()
        val client = NfsClient(connect = { Session(tree).also(sessions::add) })
        val files = client.walk(source)
        assertEquals(10, files.size)
        assertEquals(5, files.count { !it.directory })
        assertTrue(files.none { it.path.contains("循环链接") })
        assertEquals(6, sessions.sumOf { it.listed.size })
        assertTrue(sessions.size in 1..4 && sessions.all { it.closed })
        assertEquals(123L, files.first { !it.directory }.size)
        assertTrue(files.all { NfsAddress.assetPath(it.uri, source.id) == it.path })
    }

    @Test
    fun failedDirectoryScanReleasesEveryConnection() = runBlocking {
        val sessions = CopyOnWriteArrayList<Session>()
        val client =
            NfsClient(
                connect = {
                    Session(mapOf("" to listOf(NfsEntry("child", 2, 0, 0))), "child")
                        .also(sessions::add)
                },
            )
        try {
            client.walk(source)
            fail("Expected scan failure")
        } catch (_: IOException) {}
        assertTrue(sessions.all { it.closed })
    }

    @Test
    fun wrongSourceDoesNotConnect() {
        val client = NfsClient(connect = { error("must not connect") })
        assertThrows(IllegalArgumentException::class.java) {
            client.open(source, NfsAddress.assetUri("other", "01.wav"))
        }
    }

    @Test
    fun oversizedAttachmentIsRejectedBeforeReadingAndConnectionIsClosed() {
        var read = false
        var closed = false
        var fileClosed = false
        val client =
            NfsClient(
                connect = {
                    object : NfsSession {
                        override fun list(path: String) = emptyList<NfsEntry>()

                        override fun open(path: String) =
                            object : RemoteReadHandle {
                                override val length = 100L

                                override fun read(
                                    buffer: ByteArray,
                                    offset: Long,
                                    bufferOffset: Int,
                                    count: Int,
                                ): Int {
                                    read = true
                                    return 0
                                }

                                override fun close() {
                                    fileClosed = true
                                }
                            }

                        override fun close() {
                            closed = true
                        }
                    }
                },
            )
        assertThrows(IllegalArgumentException::class.java) {
            client.bytes(source, NfsAddress.assetUri(source.id, "cover.png"), 10)
        }
        assertFalse(read)
        assertTrue(closed && fileClosed)
    }
}
