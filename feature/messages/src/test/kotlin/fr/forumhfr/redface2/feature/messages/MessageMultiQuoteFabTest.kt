package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1074 — the MP « Citer N » FAB keeps editor-open and basket-clear gestures distinct. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class MessageMultiQuoteFabTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `short tap opens the editor without clearing`() {
        var opens = 0
        var clears = 0
        mount(onClick = { opens++ }, onClear = { clears++ })

        compose.onNodeWithContentDescription(FAB_LABEL)
            .assertHasClickAction()
            .performClick()

        assertEquals(1, opens)
        assertEquals(0, clears)
    }

    @Test
    fun `long press clears without opening the editor`() {
        var opens = 0
        var clears = 0
        mount(onClick = { opens++ }, onClear = { clears++ })

        compose.onNodeWithContentDescription(FAB_LABEL).performTouchInput { longClick() }

        assertEquals(0, opens)
        assertEquals(1, clears)
    }

    private fun mount(onClick: () -> Unit, onClear: () -> Unit) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                MessageMultiQuoteFab(count = 3, onClick = onClick, onClear = onClear)
            }
        }
    }

    private companion object {
        const val FAB_LABEL = "Citer 3 messages sélectionnés"
    }
}
