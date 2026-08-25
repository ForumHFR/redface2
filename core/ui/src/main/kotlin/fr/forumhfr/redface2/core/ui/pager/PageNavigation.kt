package fr.forumhfr.redface2.core.ui.pager

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R

/**
 * Policy-free page picker content shared by reading surfaces.
 *
 * The caller owns sheet hosting and decides when the picker exists. This primitive only renders
 * the bounded navigation contract from values and one callback: previous/next, direct numeric jump
 * and the compact exhaustive row for short page ranges. Invalid and current-page targets are
 * ignored here, before they can reach a feature ViewModel.
 */
@Composable
@Suppress("LongParameterList") // Independent bounds, availability flags, interaction gate and callback.
fun PageNavigation(
    currentPage: Int,
    availablePages: List<Int>,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    enabled: Boolean = true,
    onOpenPage: (Int) -> Unit,
) {
    val totalPages = availablePages.lastOrNull()?.coerceAtLeast(1) ?: 1
    val selectPage = { target: Int ->
        if (enabled && target in 1..totalPages && target != currentPage) onOpenPage(target)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { selectPage(currentPage - 1) },
                enabled = enabled && canGoPrevious && currentPage > 1,
            ) {
                Text(stringResource(R.string.pager_previous))
            }
            Text(
                text = stringResource(R.string.pager_position, currentPage, totalPages),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { selectPage(currentPage + 1) },
                enabled = enabled && canGoNext && currentPage < totalPages,
            ) {
                Text(stringResource(R.string.pager_next))
            }
        }
        PageJumpField(
            currentPage = currentPage,
            totalPages = totalPages,
            enabled = enabled,
            onOpenPage = selectPage,
        )
        if (availablePages.size in 2..PAGE_GRID_LIMIT) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                availablePages.forEach { page ->
                    if (page == currentPage) {
                        Button(onClick = {}, enabled = enabled) {
                            Text(text = page.toString())
                        }
                    } else {
                        OutlinedButton(
                            onClick = { selectPage(page) },
                            enabled = enabled,
                        ) {
                            Text(text = page.toString())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageJumpField(
    currentPage: Int,
    totalPages: Int,
    enabled: Boolean,
    onOpenPage: (Int) -> Unit,
) {
    var input by remember(currentPage) { mutableStateOf("") }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { raw -> input = coercePageJumpInput(raw, totalPages) },
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.pager_jump_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(160.dp),
        )
        TextButton(
            onClick = {
                val target = input.toIntOrNull() ?: return@TextButton
                if (target in 1..totalPages && target != currentPage) {
                    input = ""
                    onOpenPage(target)
                }
            },
            enabled = enabled,
        ) {
            Text(stringResource(R.string.pager_jump_action))
        }
    }
}

// #235 — cap input by the actual page-count width, never by a fixed digit count: topics beyond
// page 9999 remain reachable while a degenerate non-positive total still accepts one digit.
internal fun coercePageJumpInput(raw: String, totalPages: Int): String =
    raw.filter(Char::isDigit).take(maxOf(1, totalPages).toString().length)

private const val PAGE_GRID_LIMIT = 40
