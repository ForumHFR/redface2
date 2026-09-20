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
    fun `regular short window still reserves the requested field minimum`() {
        // 240 - 160 - 12 = 68 dp, enough to expose at least one 48 dp action target.
        assertEquals(
            68.dp,
            editorControlsMaxHeight(available = 240.dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT),
        )
    }

    @Test
    fun `short window stops growing controls once one action target fits`() {
        assertEquals(
            48.dp,
            editorControlsMaxHeight(available = 200.dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT),
        )
    }

    @Test
    fun `tiny window gives controls one third and restores the old landscape field height`() {
        assertEquals(
            100.dp / 3f,
            editorControlsMaxHeight(available = 100.dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT),
        )
        // 100 - 33.33 - 12 = 54.67 dp for the field, close to the 0.58.0 landscape measure.
        val fieldHeight = 100.dp -
            editorControlsMaxHeight(available = 100.dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT) -
            12.dp
        assertEquals(
            54.67f,
            fieldHeight.value,
            0.01f,
        )
    }

    @Test
    fun `invalid window remains bounded`() {
        assertEquals(
            0.dp,
            editorControlsMaxHeight(available = (-1).dp, fieldMin = EDITOR_DRAFT_MIN_HEIGHT),
        )
    }
}
