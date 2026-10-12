// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StableIdTest {
    @Test
    fun persistentIdsKeepTheOriginalTruncatedSha256Format() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223", stableId("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb924", stableId(""))
    }
}
