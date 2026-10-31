package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import fr.forumhfr.redface2.core.domain.diagnostics.SmileyPickerDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.SmileySheetPosition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter

/** No offset-per-frame logging. Height is debounced; state pairs are emitted only when they change. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmileySheetTrace(
    diagnostics: SmileyPickerDiagnostics?,
    sheet: SheetState,
    heightDp: Int,
) {
    val height = rememberUpdatedState(heightDp)
    DisposableEffect(diagnostics) {
        diagnostics?.attached()
        onDispose { diagnostics?.detached() }
    }
    LaunchedEffect(sheet, diagnostics) {
        snapshotFlow { sheet.currentValue to sheet.targetValue }.collect { (current, target) ->
            diagnostics?.sheetState(current.diagnosticPosition(), target.diagnosticPosition())
        }
    }
    LaunchedEffect(diagnostics) {
        snapshotFlow { height.value }.filter { it > 0 }.collectLatest {
            delay(150)
            diagnostics?.sheetHeight(it)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun SheetValue.diagnosticPosition() = when (this) {
    SheetValue.Hidden -> SmileySheetPosition.HIDDEN
    SheetValue.PartiallyExpanded -> SmileySheetPosition.PARTIALLY_EXPANDED
    SheetValue.Expanded -> SmileySheetPosition.EXPANDED
}
