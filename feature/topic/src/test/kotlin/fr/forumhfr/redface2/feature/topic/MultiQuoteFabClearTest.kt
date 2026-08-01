package fr.forumhfr.redface2.feature.topic

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

/**
 * #436 — the « Tout vider » action on the multi-quote FAB. The basket count FAB (« ❝N ») already
 * opens the editor on tap (#291) ; this pins the new gesture split: a SHORT tap still fires
 * [MultiQuoteFab]'s `onClick` (open the editor), while a LONG press fires `onClear` (empty the
 * whole basket). The basket itself is hoisted to `:app`, so here we only prove the FAB routes the
 * two gestures to the two distinct callbacks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class MultiQuoteFabClearTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a short tap opens the editor and leaves the basket untouched`() {
        var clicks = 0
        var clears = 0
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                MultiQuoteFab(count = 3, onClick = { clicks++ }, onClear = { clears++ })
            }
        }

        composeTestRule.onNodeWithContentDescription(FAB_LABEL)
            .assertHasClickAction()
            .performClick()

        assertEquals(1, clicks)
        assertEquals(0, clears)
    }

    @Test
    fun `a long press empties the basket and does not open the editor`() {
        var clicks = 0
        var clears = 0
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                MultiQuoteFab(count = 3, onClick = { clicks++ }, onClear = { clears++ })
            }
        }

        composeTestRule.onNodeWithContentDescription(FAB_LABEL)
            .performTouchInput { longClick() }

        assertEquals(1, clears)
        assertEquals(0, clicks)
    }

    private companion object {
        // The FAB carries the plural « Citer N … » label as contentDescription (the count rides
        // on the decorative glyph) ; count = 3 selects the `other` form.
        const val FAB_LABEL = "Citer 3 messages sélectionnés"
    }
}
