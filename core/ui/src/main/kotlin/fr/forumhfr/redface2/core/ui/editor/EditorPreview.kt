package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy

/**
 * ED-140: keep the source mounted; return focus without issuing any keyboard command.
 * The source must disable automatic keyboard opening on focus, as in EditorScaffold.
 * [mediaDiskCachePolicy] is mandatory: public posts keep ENABLED; MP surfaces must pass DISABLED.
 * [previewModifier] only decorates the covering surface; the mounted source retains its identity.
 */
@Composable
@Suppress("LongParameterList") // Live source, explicit privacy policy and covering surface semantics.
fun EditorPreview(
    visible: Boolean,
    content: PostContent,
    bodyState: TextFieldState,
    bodyFocusRequester: FocusRequester,
    mediaDiskCachePolicy: PostMediaDiskCachePolicy,
    title: String,
    previewModifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val inputMode = LocalInputModeManager.current
    var wasOpen by remember(bodyState) { mutableStateOf(false) }
    var returnFocus by remember(bodyState) { mutableStateOf(false) }
    LaunchedEffect(visible, bodyState) {
        if (!visible && wasOpen && returnFocus) {
            val selection = bodyState.selection
            bodyFocusRequester.requestFocus()
            if (bodyState.selection != selection) bodyState.edit { this.selection = selection }
        }
        wasOpen = visible
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().onFocusChanged {
            // Retain focus ownership when Tab reaches the preview toggle before opening it.
            if (it.hasFocus) returnFocus = true
            else if (!visible && inputMode.inputMode != InputMode.Keyboard) returnFocus = false
        }.focusProperties {
            onEnter = { if (visible) cancelFocusChange() }
        }.focusGroup().then(if (visible) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier)) {
            body()
        }
        if (visible) Surface(Modifier.matchParentSize().then(previewModifier)
            // Include blank preview space in hit testing so taps cannot reach the covered field.
            .pointerInput(Unit) { detectTapGestures {} }.semantics { paneTitle = title },
            color = MaterialTheme.colorScheme.surface) {
            BbcodePreview(content, Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                mediaDiskCachePolicy = mediaDiskCachePolicy)
        }
    }
}
