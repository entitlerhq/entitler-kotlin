package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.Customer
import com.entitlerhq.examples.generatedfeatures.Features
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Android: a view model that exposes whether the export button shows. */
class ExportViewModel(
    private val me: Customer,
    private val scope: CoroutineScope,
) {
    private val canExport = MutableStateFlow(false)

    /** Whether the export button shows. */
    val showExport: StateFlow<Boolean> = canExport

    /** Asks Entitler again, for example when the screen opens. */
    fun refresh() {
        scope.launch { canExport.value = me.isEntitled(Features.exportPdf) }
    }
}
