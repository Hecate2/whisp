// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException

data class AsmrQuery(
    val sourceId: String,
    val text: String = "",
    val order: String = "create_date",
    val subtitlesOnly: Boolean = false,
)

class AsmrPagingSource(
    private val loadPage: suspend (Int) -> AsmrPage,
) : PagingSource<Int, AsmrWork>() {
    override fun getRefreshKey(state: PagingState<Int, AsmrWork>): Int? =
        state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.let { it.prevKey?.plus(1) ?: it.nextKey?.minus(1) }
        }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, AsmrWork> {
        val page = params.key ?: 1
        return try {
            val result = loadPage(page)
            LoadResult.Page(
                result.works,
                if (page > 1) page - 1 else null,
                if (result.hasMore) page + 1 else null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}
