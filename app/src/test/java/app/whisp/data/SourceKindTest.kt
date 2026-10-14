// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import org.junit.Assert.*
import org.junit.Test

class SourceKindTest {
    @Test
    fun everyPersistedKindResolves() {
        assertEquals(SourceKind.LOCAL, SourceKind.of("local"))
        assertEquals(SourceKind.SMB, SourceKind.of("smb"))
        assertEquals(SourceKind.NFS, SourceKind.of("nfs"))
        assertEquals(SourceKind.WEBDAV, SourceKind.of("webdav"))
        assertEquals(SourceKind.ASMRONE, SourceKind.of("asmrone"))
        assertEquals(SourceKind.DLSITE, SourceKind.of("dlsite"))
    }

    @Test
    fun unknownKindResolvesToNull() {
        assertNull(SourceKind.of(""))
        assertNull(SourceKind.of("ftp"))
    }

    @Test
    fun entityExposesItsKind() {
        val source = SourceEntity("id", "name", "smb", "root")
        assertEquals(SourceKind.SMB, source.kindType)
    }
}
