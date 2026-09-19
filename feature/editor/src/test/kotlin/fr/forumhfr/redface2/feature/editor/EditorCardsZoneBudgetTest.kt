package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.editor.editorControlsMaxHeight
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #555/#447 — PostEditorScreen uses the shared controls budget with a 160 dp draft reserve.
 */
class EditorCardsZoneBudgetTest {

    @Test
    fun `roomy display keeps the historical cap`() {
        // 700 − 160 − 12 = 528 → capped at 360 (banner + « Tout vider » + ~4 cards).
        assertEquals(360.dp, editorControlsMaxHeight(available = 700.dp, fieldMin = 160.dp))
    }

    @Test
    fun `short window hands the top zone only what the field minimum leaves`() {
        // At 300 dp, the 128 dp remainder still beats the 40% short-window share.
        assertEquals(128.dp, editorControlsMaxHeight(available = 300.dp, fieldMin = 160.dp))
        // At 200 dp, preserving useful controls takes 40%; the field keeps the larger 60% share.
        assertEquals(80.dp, editorControlsMaxHeight(available = 200.dp, fieldMin = 160.dp))
    }

    @Test
    fun `tiny window keeps controls reachable without overtaking the field`() {
        assertEquals(40.dp, editorControlsMaxHeight(available = 100.dp, fieldMin = 160.dp))
    }

    @Test
    fun `old field break-even now uses the responsive share`() {
        assertEquals(68.8.dp, editorControlsMaxHeight(available = 172.dp, fieldMin = 160.dp))
    }

    @Test
    fun `w360dp h640dp leaves more than the field minimum below roomy controls`() {
        val controls = editorControlsMaxHeight(available = 640.dp, fieldMin = 160.dp)

        assertEquals(360.dp, controls)
        assertEquals(268.dp, 640.dp - controls - 12.dp)
    }

    @Test
    fun `w320dp h568dp leaves more than the field minimum below roomy controls`() {
        val controls = editorControlsMaxHeight(available = 568.dp, fieldMin = 160.dp)

        assertEquals(360.dp, controls)
        assertEquals(196.dp, 568.dp - controls - 12.dp)
    }
}
