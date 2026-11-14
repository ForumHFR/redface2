package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.editor.EditorPreviewDirection.Vertical
import fr.forumhfr.redface2.core.model.editor.EditorPreviewKey
import fr.forumhfr.redface2.core.model.editor.EditorPreviewWidthClass.Compact
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorPreviewStateTest {
    private val key = EditorPreviewKey(Vertical, Compact)

    @Test fun `late hydration never rolls back an explicit gesture and releases write in order`() = runTest {
        val disk = MutableStateFlow<Map<EditorPreviewKey, Float>>(emptyMap())
        val writes = mutableListOf<Pair<EditorPreviewKey, Float>>()
        val firstWrite = CompletableDeferred<Unit>()
        val state = EditorPreviewState(disk, { writtenKey, value ->
            writes += writtenKey to value
            if (value == 180f) firstWrite.await()
        }, backgroundScope)
        state.rememberPosition(key, 180f)
        state.rememberPosition(key, 240f)
        runCurrent()
        disk.value = mapOf(key to 400f)
        runCurrent()
        assertEquals(240f, state.sourceDp(key))
        firstWrite.complete(Unit)
        runCurrent()
        assertEquals(listOf(key to 180f, key to 240f), writes)
    }

    @Test fun `restoration clamps to the new window without overwriting the remembered dp`() {
        val state = EditorPreviewState()
        state.rememberPosition(key, 400f)
        val bounds = requireNotNull(editorPreviewBounds(400f, Vertical, true, 100f))
        assertEquals(282f, bounds.restore(state.sourceDp(key)), 0f)
        assertEquals(400f, state.sourceDp(key))
    }
}
