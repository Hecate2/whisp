// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch

/** Explicit actions complete immediately; only removal of the sheet waits for its exit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WhispSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val sheet =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val dismiss = {
        if (!closing) {
            closing = true
            scope.launch {
                try {
                    sheet.hide()
                    if (!sheet.isVisible) onDismissRequest()
                } finally {
                    closing = false
                }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheet,
        containerColor = Surface,
    ) {
        content(dismiss)
    }
}
