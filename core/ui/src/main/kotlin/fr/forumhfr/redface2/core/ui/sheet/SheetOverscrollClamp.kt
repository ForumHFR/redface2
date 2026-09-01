package fr.forumhfr.redface2.core.ui.sheet

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * #1193 — directional clamp for a `ModalBottomSheet`'s residual top overscroll.
 *
 * With `skipPartiallyExpanded = true` a tall, scrollable sheet anchors at [SheetValue.Expanded].
 * Once its inner Lazy list is already at its top butée, any remaining UPWARD scroll delta / fling
 * velocity bubbles out to the sheet's own nested-scroll connection, whose M3 1.4.0 spring settle
 * overshoots ABOVE the Expanded anchor and oscillates. This connection swallows exactly that
 * residual — the upward leftover (`available.y < 0`) once `currentValue` is Expanded — and lets
 * everything else through: the content still scrolls both directions, swipe-down-to-dismiss and the
 * drag handle are untouched (deliberately no `onPreScroll` / `onPreFling`).
 *
 * Placement matters: apply it on the content container that WRAPS the `LazyColumn` /
 * `LazyVerticalGrid`, so it is the closest nested-scroll parent and intercepts the residual in
 * `onPostScroll` / `onPostFling` BEFORE the sheet's connection (post-phase is dispatched
 * inner-to-outer) sees it — never on the `ModalBottomSheet`'s own `modifier`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Modifier.clampSheetTopOverscroll(sheetState: SheetState): Modifier {
    val connection = remember(sheetState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = if (sheetState.currentValue == SheetValue.Expanded && available.y < 0f) {
                Offset(0f, available.y)
            } else {
                Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if (sheetState.currentValue == SheetValue.Expanded && available.y < 0f) {
                    Velocity(0f, available.y)
                } else {
                    Velocity.Zero
                }
        }
    }
    return this.nestedScroll(connection)
}
