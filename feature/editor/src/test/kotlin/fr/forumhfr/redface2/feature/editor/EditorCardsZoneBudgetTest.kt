package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.unit.Dp
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
            EDITOR_DRAFT_MIN_HEIGHT.value,
            fieldHeight(available = 300.dp),
            0.01f,
        )
        assertEquals(
            EDITOR_DRAFT_MIN_HEIGHT.value,
            fieldHeight(available = 330.dp),
            0.01f,
        )
    }

    @Test
    fun `landscape viewport restores about 55 dp to the field`() {
        assertEquals(
            54.67f,
            fieldHeight(available = 100.dp),
            0.01f,
        )
    }

    private fun fieldHeight(available: Dp): Float = (
        available -
            editorControlsMaxHeight(available = available, fieldMin = EDITOR_DRAFT_MIN_HEIGHT) -
            12.dp
        ).value
}
