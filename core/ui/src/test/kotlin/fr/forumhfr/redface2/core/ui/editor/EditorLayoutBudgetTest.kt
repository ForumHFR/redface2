package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure height budget shared by the topic and private-message composers. */
class EditorLayoutBudgetTest {

    @Test
    fun `roomy display keeps the historical controls cap`() {
        assertEquals(
            360.dp,
            editorControlsMaxHeight(available = 700.dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT),
        )
    }

    @Test
    fun `regular window reserves the requested field minimum plus spacing`() {
        // 360 - 160 - 12 = 188; the controls use the remainder and leave exactly 160 dp.
        assertEquals(
            188.dp,
            editorControlsMaxHeight(available = 360.dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT),
        )
    }

    @Test
    fun `short window gives controls forty percent instead of collapsing either zone`() {
        // The 68 dp remainder would leave metadata nearly unusable, so the responsive share wins.
        assertEquals(
            96.dp,
            editorControlsMaxHeight(available = 240.dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT),
        )
    }

    @Test
    fun `tiny and invalid windows remain bounded`() {
        assertEquals(
            40.dp,
            editorControlsMaxHeight(available = 100.dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT),
        )
        assertEquals(
            0.dp,
            editorControlsMaxHeight(available = (-1).dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT),
        )
    }
}
