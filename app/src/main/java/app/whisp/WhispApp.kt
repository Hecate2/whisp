// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.app.Application
import app.whisp.data.LibraryRepository

class WhispApp : Application() {
    lateinit var repository: LibraryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = LibraryRepository(this)
    }
}
