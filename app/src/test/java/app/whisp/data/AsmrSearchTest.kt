// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import org.junit.Assert.*
import org.junit.Test

class AsmrSearchTest {
    @Test
    fun workNumbersAcceptFullWidthInputAndKnownWorkLinks() {
        assertEquals(1L, asmrWorkNumber(" ｒｊ０００００００１ "))
        assertEquals(1L, asmrWorkNumber("https://asmr.one/work/RJ00000001"))
        assertEquals(
            1L,
            asmrWorkNumber(
                "https://www.dlsite.com/maniax/work/=/product_id/RJ00000001.html?locale=ja_JP",
            ),
        )
        listOf(
                "123456",
                "作品 RJ00000001",
                "https://unknown.example/work/RJ00000001",
                "https://asmr.one.evil.example/work/RJ00000001",
                "RJ00000000",
                "https://user:pass@asmr.one/work/RJ00000001",
            )
            .forEach { assertNull(asmrWorkNumber(it)) }
    }

    @Test
    fun combinedFacetQueryUsesNamesAndSessionRoundTrips() {
        val query =
            AsmrQuery(
                "source",
                " 示例 ",
                "dl_count",
                true,
                listOf(
                    AsmrFacet("va", "示例声优"),
                    AsmrFacet("circle", "示例社团"),
                    AsmrFacet("tagw", "示例标签"),
                ),
            )
        assertEquals("示例 \$va:示例声优\$ \$circle:示例社团\$ \$tagw:示例标签\$", query.serverText)
        assertEquals(query, decodeAsmrQuery(encodeAsmrQuery(query)))
        assertNull(decodeAsmrQuery("invalid"))
        assertThrows(IllegalArgumentException::class.java) { AsmrFacet("va", "示例\$circle:其他\$") }
        assertTrue(query.searching)
        assertFalse(AsmrQuery("source").searching)
    }
}
