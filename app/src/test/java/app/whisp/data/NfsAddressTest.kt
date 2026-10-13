// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import org.junit.Assert.*
import org.junit.Test

class NfsAddressTest {
    @Test
    fun normalizedAddressRetainsProtocolAndIdentity() {
        val address =
            NfsAddress.parse("nfs://NAS.EXAMPLE/media/Audio/?version=4.2&uid=1000&gid=1001")
        assertEquals(
            "nfs://nas.example/media/Audio?version=4.2&uid=1000&gid=1001",
            address.normalized,
        )
        assertEquals(address, NfsAddress.parse(address.normalized))
    }

    @Test
    fun ipv6PortsAndMountPortRoundTrip() {
        val address = NfsAddress.parse("nfs://[::1]:12049/media?version=3&mountport=12050")
        assertEquals(12049, address.port)
        assertEquals(12050, address.mountPort)
        assertEquals(address, NfsAddress.parse(address.normalized))
    }

    @Test
    fun unicodeAndReservedCharactersRemainFileNames() {
        val path = "音声/01 + % ? # 😀\\.wav"
        val uri = NfsAddress.assetUri("source-test", path)
        assertEquals(path, NfsAddress.assetPath(uri, "source-test"))
        assertFalse(uri.contains("😀"))
    }

    @Test
    fun credentialsUnknownOptionsAndTraversalAreRejected() {
        for (value in
            listOf(
                "nfs://user:password@nas.example/media",
                "nfs://nas.example/media#fragment",
                "nfs://nas.example/media?sec=krb5",
                "nfs://nas.example/media?uid=-1",
                "nfs://nas.example/media?version=4.1",
                "nfs://nas.example/media?gid=1&gid=2",
                "nfs://nas.example/a/%2e%2e/b",
                "nfs://nas.example/a%2fb",
                "nfs://nas.example/%00",
            )) {
            assertThrows(IllegalArgumentException::class.java) { NfsAddress.parse(value) }
        }
    }

    @Test
    fun mediaUrisAreBoundToTheirSource() {
        val uri = NfsAddress.assetUri("first", "audio/01.wav")
        assertThrows(IllegalArgumentException::class.java) { NfsAddress.assetPath(uri, "second") }
        for (path in listOf("../audio.wav", "folder/../audio.wav", "a\u0000.wav")) assertThrows(
            IllegalArgumentException::class.java,
        ) {
            NfsAddress.assetUri("first", path)
        }
    }
}
