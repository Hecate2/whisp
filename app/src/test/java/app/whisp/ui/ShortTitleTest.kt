// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortTitleTest {
    @Test
    fun plainTitleIsUnchanged() {
        assertEquals("测试作品", shortTitle("测试作品"))
    }

    @Test
    fun leadingPartBeforeSeparatorIsDropped() {
        assertEquals("后篇", shortTitle("前篇～后篇"))
    }

    @Test
    fun surroundingMarksAndSpacesAreTrimmed() {
        assertEquals("后篇", shortTitle("～后篇～"))
        assertEquals("标题", shortTitle("【标题】"))
        assertEquals("标题", shortTitle("  标题  "))
    }

    @Test
    fun emptyRemainderStaysEmpty() {
        assertEquals("", shortTitle("前篇～"))
    }
}
