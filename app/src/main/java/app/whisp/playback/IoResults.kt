// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Bridges a suspending IO lookup to the ListenableFuture media-session callbacks expect. */
internal fun <T> CoroutineScope.ioResult(block: suspend () -> T): ListenableFuture<T> {
    val result = SettableFuture.create<T>()
    val job =
        launch(Dispatchers.IO) {
            try {
                result.set(block())
            } catch (e: CancellationException) {
                result.cancel(false)
            } catch (e: Exception) {
                result.setException(e)
            }
        }
    job.invokeOnCompletion { if (it is CancellationException) result.cancel(false) }
    result.addListener(
        { if (result.isCancelled) job.cancel() },
        MoreExecutors.directExecutor(),
    )
    return result
}
