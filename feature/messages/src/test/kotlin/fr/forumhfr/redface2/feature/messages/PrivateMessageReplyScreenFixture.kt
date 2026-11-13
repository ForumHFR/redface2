@file:Suppress("MatchingDeclarationName") // Shared reply assembly and its private picker registry.

package fr.forumhfr.redface2.feature.messages

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController

/** Exercise the production assembly and the same synchronous event deliveries as the VM. */
@Composable
@Suppress("LongParameterList") // Tests may retain field, chrome and picker independently across recreation.
internal fun PrivateMessageReplyTestContent(
    state: PrivateMessageReplyUiState,
    body: TextFieldState = remember { TextFieldState(state.bodyText) },
    actions: PrivateMessageReplyChromeActions = remember { PrivateMessageReplyChromeFixture().actions },
    events: PrivateMessageReplyEvents? = null,
    memory: PrivateMessageReplyChromeMemory = remember { PrivateMessageReplyChromeMemory() },
    autoOpen: Boolean = false,
    scroll: ScrollState = rememberScrollState(),
    smileys: SmileyPickerController? = null,
    transformation: InputTransformation = InputTransformation {},
    bodyCaretRevealResetKey: () -> Int = { 0 },
    onOpenEditorSettings: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val picker = smileys ?: remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
    val localEvents = events ?: remember { PrivateMessageReplyEvents() }
    val previous = remember { arrayOf(state.copy(submitError = null, uploadError = null)) }
    SideEffect {
        if (events == null) localEvents.observe(previous[0], state)
        previous[0] = state
    }
    val height = LocalConfiguration.current.screenHeightDp
    val fallbackOwner = remember { ReplyTestActivityResultOwner() }
    val owner = LocalActivityResultRegistryOwner.current ?: fallbackOwner
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
        PrivateMessageReplyContent(state, autoOpen, body, transformation, actions, picker,
            bodyScrollState = scroll, chromeEvents = localEvents, chromeMemory = memory,
            windowMetrics = EditorWindowMetrics(height.dp, 0.dp, 0.dp),
            bodyCaretRevealResetKey = bodyCaretRevealResetKey, onOpenEditorSettings = onOpenEditorSettings)
    }
}

private class ReplyTestActivityResultOwner : ActivityResultRegistryOwner {
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            error("Image picker launches require an activity-backed test host")
        }
    }
}
