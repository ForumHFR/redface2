package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.editor.EditorPreviewKey
import fr.forumhfr.redface2.core.ui.R
import java.io.IOException
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * VM-owned, never saved in a Bundle: rotation keeps choices, process death starts with closed preview
 * (the existing VM visibility state) and reloads only dimensions. No private text enters preferences.
 * The default instance is a persistence-free component bench.
 */
class EditorPreviewState(
    positionsFlow: Flow<Map<EditorPreviewKey, Float>> = emptyFlow(),
    private val persistPosition: suspend (EditorPreviewKey, Float) -> Unit = { _, _ -> },
    private val scope: CoroutineScope? = null,
) {
    constructor(preferences: UserPreferencesRepository, scope: CoroutineScope) : this(
        preferences.observeEditorPreviewPositions(), preferences::setEditorPreviewPosition, scope,
    )

    private val chosen = mutableMapOf<EditorPreviewKey, Float>()
    private val writes = Mutex()
    private var positions by mutableStateOf<Map<EditorPreviewKey, Float>>(emptyMap())

    init {
        scope?.launch {
            positionsFlow.collect { positions = it + chosen }
        }
    }

    fun sourceDp(key: EditorPreviewKey): Float? = positions[key]

    fun rememberPosition(key: EditorPreviewKey, sourceDp: Float) {
        require(sourceDp.isFinite() && sourceDp > 0f)
        chosen[key] = sourceDp
        positions = positions + (key to sourceDp)
        scope?.launch {
            // Serialize releases; an older asynchronous write must never beat a newer gesture.
            writes.withLock {
                try {
                    persistPosition(key, sourceDp)
                } catch (_: IOException) {
                    Logger.getLogger("EditorPreview").warning("Unable to persist preview divider position")
                }
            }
        }
    }
}

val LocalEditorPreviewState = staticCompositionLocalOf<EditorPreviewState?> { null }

/** Session-only return ticket: retained by the scaffold host, never persisted with divider positions. */
internal class EditorPreviewFocusMemory {
    var wasOpen = false
    var returnFocus = false
    var hiddenByIme by mutableStateOf(false)

    fun reset() { wasOpen = false; returnFocus = false; hiddenByIme = false }
}

internal val LocalEditorPreviewFocusMemory = staticCompositionLocalOf<EditorPreviewFocusMemory?> { null }

/** Attach to both Eye buttons: its close action stays available while the preview is not rendered. */
@Composable
internal fun Modifier.editorPreviewVisibilitySemantics(visible: Boolean): Modifier {
    val hidden = visible && LocalEditorPreviewFocusMemory.current?.hiddenByIme == true
    val description = if (hidden) stringResource(R.string.editor_preview_hidden_while_typing) else null
    return semantics {
        if (description != null) {
            stateDescription = description
            liveRegion = LiveRegionMode.Polite
        }
    }
}

/** The scaffold supplies the same current-window compact-height decision as its chrome. */
internal val LocalEditorPreviewCompactHeight = staticCompositionLocalOf { false }

/** Scaffold suppresses automatic IME requests before returning from a keyboard-focused preview control. */
internal val LocalEditorPreviewReturnFocus = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Only an unmeasured layout may wait; a determined covering fallback consumes restoration. */
internal val LocalEditorPreviewBodyFocusAvailability = staticCompositionLocalOf { EditorBodyFocusAvailability.Ready }

/** Includes zero-height layouts, which cannot publish a three-line minimum. */
internal val LocalEditorPreviewFieldHeight = staticCompositionLocalOf<((Int) -> Unit)?> { null }

/** Reported by the mounted, decorated BTF2 field in pixels, including label and internal padding. */
internal val LocalEditorPreviewFieldMinimum = staticCompositionLocalOf<((Int, Int) -> Unit)?> { null }
