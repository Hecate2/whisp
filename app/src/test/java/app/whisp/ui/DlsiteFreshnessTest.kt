// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import app.whisp.data.DlsiteDetails
import app.whisp.data.DlsiteStatistics
import org.junit.Assert.*
import org.junit.Test

class DlsiteFreshnessTest {
    @Test
    fun freshProductSkipsNetwork() {
        val cached = DlsiteDetails("RJ1", completeProduct = true, productFetchedAt = 1_000_000)
        assertTrue(DlsiteDetailsLoader.isFresh(cached, "product", 1_000_000 + 24 * 60 * 60 * 1000L))
    }

    @Test
    fun staleProductRefetches() {
        val cached = DlsiteDetails("RJ1", completeProduct = true, productFetchedAt = 0)
        assertFalse(DlsiteDetailsLoader.isFresh(cached, "product", 8 * 24 * 60 * 60 * 1000L))
        assertFalse(DlsiteDetailsLoader.isFresh(DlsiteDetails("RJ1"), "product", 1_000_000))
        assertFalse(DlsiteDetailsLoader.isFresh(null, "product", 1_000_000))
    }

    @Test
    fun descriptionNeedsAnyFetch() {
        assertTrue(
            DlsiteDetailsLoader.isFresh(
                DlsiteDetails("RJ1", descriptionFetchedAt = 5),
                "description",
                9,
            ),
        )
        assertFalse(DlsiteDetailsLoader.isFresh(DlsiteDetails("RJ1"), "description", 9))
    }

    @Test
    fun statisticsExpireAfterOneDay() {
        val fresh = DlsiteDetails("RJ1", statistics = DlsiteStatistics(fetchedAt = 1_000_000))
        assertTrue(DlsiteDetailsLoader.isFresh(fresh, "statistics", 1_000_000 + 60_000))
        assertFalse(
            DlsiteDetailsLoader.isFresh(fresh, "statistics", 1_000_000 + 25 * 60 * 60 * 1000L),
        )
        assertFalse(DlsiteDetailsLoader.isFresh(DlsiteDetails("RJ1"), "statistics", 1_000_000))
    }
}
