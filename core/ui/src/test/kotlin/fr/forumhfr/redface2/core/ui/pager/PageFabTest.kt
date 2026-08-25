package fr.forumhfr.redface2.core.ui.pager

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pins the shared #820/#822 click and long-click split independently from feature clusters. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PageFabTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a short tap invokes exactly the step callback`() {
        var steps = 0
        var jumps = 0
        mount(onClick = { steps++ }, onLongClick = { jumps++ })

        compose.onNodeWithContentDescription(FAB_LABEL)
            .assertHasClickAction()
            .performClick()

        assertEquals(1, steps)
        assertEquals(0, jumps)
    }

    @Test
    fun `a long press invokes exactly the boundary callback`() {
        var steps = 0
        var jumps = 0
        mount(onClick = { steps++ }, onLongClick = { jumps++ })

        compose.onNodeWithContentDescription(FAB_LABEL).performTouchInput { longClick() }

        assertEquals(0, steps)
        assertEquals(1, jumps)
    }

    @Test
    fun `a disabled page FAB exposes its disabled state`() {
        mount(enabled = false)

        compose.onNodeWithContentDescription(FAB_LABEL).assertIsNotEnabled()
    }

    private fun mount(
        enabled: Boolean = true,
        onClick: () -> Unit = {},
        onLongClick: () -> Unit = {},
    ) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PageFab(
                    description = FAB_LABEL,
                    iconRes = R.drawable.ic_chevron_left,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onLongClickLabel = LONG_PRESS_LABEL,
                    enabled = enabled,
                )
            }
        }
    }

    private companion object {
        const val FAB_LABEL = "Page précédente"
        const val LONG_PRESS_LABEL = "Aller à la première page"
    }
}
