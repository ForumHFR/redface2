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
 * #822 — a LONG press on the ‹/› page FABs jumps straight to the first/last page. [PageFab] moved
 * from a real SmallFloatingActionButton to the hand-rolled #820 pattern (MultiQuoteFab), because
 * the real FAB's inner clickable swallows the pointer input of a stacked combinedClickable — the
 * exact trap [MultiQuoteFabClearTest] pinned for « Tout vider ». This pins the new gesture split:
 * a SHORT tap still fires `onClick` (the single-page step), a LONG press fires `onLongClick` (the
 * first/last jump). The page targets themselves are wired in `TopicBottomActionsHost` ; here we
 * only prove the FAB routes the two gestures to the two distinct callbacks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PageFabLongPressTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a short tap steps a single page and does not jump`() {
        var clicks = 0
        var longPresses = 0
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PageFab(
                    description = FAB_LABEL,
                    iconRes = fr.forumhfr.redface2.core.ui.R.drawable.ic_chevron_left,
                    onClick = { clicks++ },
                    onLongClick = { longPresses++ },
                    onLongClickLabel = LONG_PRESS_LABEL,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(FAB_LABEL)
            .assertHasClickAction()
            .performClick()

        assertEquals(1, clicks)
        assertEquals(0, longPresses)
    }

    @Test
    fun `a long press jumps to the boundary page and does not step`() {
        var clicks = 0
        var longPresses = 0
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PageFab(
                    description = FAB_LABEL,
                    iconRes = fr.forumhfr.redface2.core.ui.R.drawable.ic_chevron_left,
                    onClick = { clicks++ },
                    onLongClick = { longPresses++ },
                    onLongClickLabel = LONG_PRESS_LABEL,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(FAB_LABEL)
            .performTouchInput { longClick() }

        assertEquals(1, longPresses)
        assertEquals(0, clicks)
    }

    private companion object {
        // The FAB carries the a11y label as contentDescription (the chevron icon is decorative) ;
        // the long-press affordance is announced through onLongClickLabel for TalkBack.
        const val FAB_LABEL = "Page précédente"
        const val LONG_PRESS_LABEL = "Aller à la première page"
    }
}
