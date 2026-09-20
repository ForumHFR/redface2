package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.editor.EDITOR_DRAFT_MIN_HEIGHT
import fr.forumhfr.redface2.core.ui.editor.editorControlsMaxHeight
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #555/#447 — screen-specific checks for the post editor's shared controls budget.
 */
class EditorCardsZoneBudgetTest {

    @Test
    fun `s9 ime viewport reserves 160 dp for the field across the measured range`() {
        assertEquals(
            128.dp,
            editorControlsMaxHeight(available = 300.dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT),
        )
        assertEquals(
            158.dp,
            editorControlsMaxHeight(available = 330.dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT),
        )
    }

    @Test
    fun `landscape viewport restores about 55 dp to the field`() {
        assertEquals(
            100.dp / 3f,
            editorControlsMaxHeight(available = 100.dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT),
        )
    }
}
