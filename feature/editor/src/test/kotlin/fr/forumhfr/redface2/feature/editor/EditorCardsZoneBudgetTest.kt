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
    fun `short window hands the top zone only what the field minimum leaves`() {
        // At 300 dp, the 128 dp remainder still beats the 40% short-window share.
        assertEquals(
            128.dp,
            editorControlsMaxHeight(available = 300.dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT),
        )
        // At 200 dp, preserving useful controls takes 40%; the field keeps the larger 60% share.
        assertEquals(
            80.dp,
            editorControlsMaxHeight(available = 200.dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT),
        )
    }

    @Test
    fun `old field break-even now uses the responsive share`() {
        assertEquals(
            68.8.dp,
            editorControlsMaxHeight(available = 172.dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT),
        )
    }

    @Test
    fun `w360dp h640dp leaves more than the field minimum below roomy controls`() {
        val controls = editorControlsMaxHeight(
            available = 640.dp,
            fieldMin = EDITOR_DRAFT_MIN_HEIGHT,
        )

        assertEquals(360.dp, controls)
        assertEquals(268.dp, 640.dp - controls - 12.dp)
    }

    @Test
    fun `w320dp h568dp leaves more than the field minimum below roomy controls`() {
        val controls = editorControlsMaxHeight(
            available = 568.dp,
            fieldMin = EDITOR_DRAFT_MIN_HEIGHT,
        )

        assertEquals(360.dp, controls)
        assertEquals(196.dp, 568.dp - controls - 12.dp)
    }
}
