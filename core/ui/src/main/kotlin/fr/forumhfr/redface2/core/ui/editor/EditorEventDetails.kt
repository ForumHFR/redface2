package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.ui.R

/**
 * ED-45 detail for the snapshot delivered by OpenDetails; null closes the overlay.
 * Keep this container mounted around [body]. Never substitute the bar's current visible event
 * for the open snapshot: a replacement must not be acknowledged by an older close callback.
 * [onClose] must dispatch CloseDetails(key), then consume ReturnToBody by closing the overlay;
 * [onReturnToBody] runs once after [event] becomes null. This is visual acknowledgement only, not resolution of
 * a durable submission or abandonment of a quote. [onDismiss] handles the explicit Masquer remedy separately;
 * the host owns local or durable acknowledgement. CompleteHeader is handled by EditorHeaderPanel.
 * Supply the Message field's [bodyState] so focus transfers preserve its selection. As with
 * [EditorHeaderPanel], the host captures IME visibility before opening. [onReturnToBody] enqueues a
 * Surface return in [EditorScaffold]; its effect preserves selection and restores focus and the captured
 * IME state after the input options update, provided the body accepts focus.
 *
 * [onReload] relaunches the host's existing GET only. Supply [onIgnoreQuote] only after two failures
 * of the SAME pending quote request; it is a distinct remedy, never a replacement for Reload.
 * [content] adds host-provided context below the integral catalogue detail (no raw server text).
 */
@Composable
@Suppress("LongParameterList") // Each remedy has its own callback and exact event identity.
fun EditorEventDetails(
    event: EditorEvent?,
    bodyState: TextFieldState,
    onClose: (EventKey) -> Unit,
    onReload: (EventKey) -> Unit,
    onReturnToBody: () -> Unit,
    modifier: Modifier = Modifier,
    onIgnoreQuote: ((EventKey) -> Unit)? = null,
    onDismiss: (EventKey) -> Unit,
    body: @Composable () -> Unit,
    content: @Composable ColumnScope.(EditorEvent) -> Unit = {},
) {
    require(event == null || event.action == EditorEventAction.Details) {
        "Only a detail event may open EditorEventDetails"
    }
    // Resolve only the layer owned here. Sheets/tooltips are registered later by the host.
    val back = resolveEditorBack(EditorBackState(eventDetailKey = event?.key))
    val close = { if (back is EditorBackAction.CloseEventDetails) onClose(back.key) }
    EditorPanelOverlay(
        visible = event != null,
        title = event?.summary?.resolve().orEmpty(),
        bodyState = bodyState,
        onClose = close,
        onReturnToBody = onReturnToBody,
        modifier = modifier,
        body = body,
    ) { closeFocus ->
        if (event != null) {
            Text(requireNotNull(event.detail).resolve())
            content(event)
            val error = (event.content as? EditorEventContent.Problem)?.error
            if (error?.action == EditorErrorAction.Reload) TextButton(onClick = { onReload(event.key) }) {
                Text(stringResource(R.string.editor_action_reload))
            }
            if (error?.cause == EditorCause.QuoteFetchFailed && onIgnoreQuote != null) {
                TextButton(onClick = { onIgnoreQuote(event.key) }) {
                    Text(stringResource(R.string.editor_action_ignore_quote))
                }
            }
            TextButton(onClick = { onDismiss(event.key) }, modifier = Modifier.focusRequester(closeFocus)) {
                Text(stringResource(R.string.editor_action_dismiss))
            }
        }
    }
}
