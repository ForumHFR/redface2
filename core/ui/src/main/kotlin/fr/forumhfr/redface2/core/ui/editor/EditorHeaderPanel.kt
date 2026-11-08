package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R

/**
 * ED-101/113 container only: the host supplies fields, validation and their enabled state.
 * Keep this composable mounted, including when [isOpen] is false, and put the editor in [body].
 * Supply the Message field's [bodyState] and attach [bodyFocusRequester] to that field.
 * [initialFocusRequester], when supplied, must be attached to a field in [content] (title or recipients);
 * otherwise Return receives focus.
 * The host opens this panel on CompleteHeader and closes it in [onClose]. No CSV mapping lives here.
 * Insets and the editor's outer BackHandler belong to the host, composed before this container.
 */
@Composable
@Suppress("LongParameterList") // Independent slots, visibility, labels, focus targets and close callback.
fun EditorHeaderPanel(
    isOpen: Boolean,
    title: String,
    bodyFocusRequester: FocusRequester,
    bodyState: TextFieldState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocusRequester: FocusRequester? = null,
    body: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    EditorPanelOverlay(
        isOpen, title, bodyFocusRequester, bodyState, onClose, modifier, initialFocusRequester, body,
    ) { closeFocus ->
        TextButton(onClick = onClose, modifier = Modifier.focusRequester(closeFocus)) {
            Text(stringResource(R.string.editor_panel_return_to_body))
        }
        content()
    }
}

/** Same composition boundary for both panels: never key or conditionally compose the body. */
@Composable
@Suppress("LongParameterList") // Shared overlay boundary; keep body and panel slots separate.
internal fun EditorPanelOverlay(
    visible: Boolean,
    title: String,
    bodyFocusRequester: FocusRequester,
    bodyState: TextFieldState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocusRequester: FocusRequester? = null,
    body: @Composable () -> Unit,
    content: @Composable ColumnScope.(FocusRequester) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val closeFocus = remember { FocusRequester() }
    var wasOpen by remember(bodyState) { mutableStateOf(false) }
    Box(modifier) {
        Box(Modifier.fillMaxSize().focusProperties {
            // canFocus=false alone only affects one target, not a subtree. Refuse entry into
            // this group even for a descendant's programmatic request while it is covered.
            onEnter = { if (visible) cancelFocusChange() }
        }.focusGroup().then(if (visible) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier)) {
            body()
        }
        // Registered after the body's handler: a panel opened on entry consumes the first Back.
        BackHandler(enabled = visible, onBack = onClose)
        if (visible) Surface(
            modifier = Modifier.matchParentSize().semantics {
                paneTitle = title
                isTraversalGroup = true
            },
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                content(closeFocus)
            }
        }
    }
    LaunchedEffect(visible, initialFocusRequester, bodyState) {
        val selectionBeforeFocusChange = bodyState.selection
        if (visible) {
            // Also release a body's captured focus; merely hiding semantics does not release it.
            focusManager.clearFocus(force = true)
            (initialFocusRequester ?: closeFocus).requestFocus()
        } else if (wasOpen) {
            bodyFocusRequester.requestFocus()
        }
        // BTF2 collapses selection on focus loss. A panel must preserve both endpoints,
        // including a reversed range, without replacing text or adding an undo entry.
        if (bodyState.selection != selectionBeforeFocusChange) bodyState.edit {
            selection = selectionBeforeFocusChange
        }
        wasOpen = visible
    }
}
