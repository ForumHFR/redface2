package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import kotlin.math.ceil

/** ED-62: actual text metrics and decoration, independently of text length and unused viewport space. */
internal class BbcodeFieldMeasurements {
    var textLayout by mutableStateOf<TextLayoutResult?>(null)
    // -1 is unmeasured; zero is a completed layout with no space for the field.
    var fieldHeight by mutableIntStateOf(-1)
    var textCapacity by mutableIntStateOf(Constraints.Infinity)

    val minimumHeight: Int?
        get() {
            val text = textLayout ?: return null
            if (fieldHeight <= 0 || textCapacity == Constraints.Infinity) return null
            val threeLines = if (text.lineCount >= 3) text.getLineBottom(2) - text.getLineTop(0)
                else (text.getLineBottom(0) - text.getLineTop(0)) * 3
            // OutlinedTextField constrains its inner field after subtracting label/padding. Unlike
            // the measured inner height, that maximum includes the unused space of an empty field.
            val decoration = (fieldHeight - textCapacity).coerceAtLeast(0)
            return ceil(threeLines).toInt() + decoration
        }
}

@Composable
internal fun rememberBbcodeFieldMeasurements(
    state: TextFieldState,
    onMinimumHeightMeasured: ((Int, Int) -> Unit)?,
): BbcodeFieldMeasurements {
    val measurements = remember(state) { BbcodeFieldMeasurements() }
    val callback by rememberUpdatedState(onMinimumHeightMeasured)
    val heightCallback by rememberUpdatedState(LocalEditorPreviewFieldHeight.current)
    val minimum = measurements.minimumHeight
    LaunchedEffect(minimum, measurements.fieldHeight, LocalDensity.current) {
        if (measurements.fieldHeight >= 0) heightCallback?.invoke(measurements.fieldHeight)
        minimum?.let { callback?.invoke(it, measurements.fieldHeight) }
    }
    return measurements
}
