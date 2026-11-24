package fr.forumhfr.redface2.core.ui.editor

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorActionBarDensityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w360dp-h640dp-471dpi")
    fun `send and tools display at 471 dpi`() = assertBarDisplayedAtDensity(471)

    @Test
    @Config(qualifiers = "w360dp-h640dp-544dpi")
    fun `send and tools display at 544 dpi`() = assertBarDisplayedAtDensity(544)

    private fun assertBarDisplayedAtDensity(expectedDpi: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(expectedDpi, context.resources.displayMetrics.densityDpi)
        compose.setContent {
            MaterialTheme {
                EditorActionBar(
                    state = EditorBarState(),
                    actions = EditorActionBarActions(
                        onGesture = {}, onBbcode = {}, onBodyFocus = {}, onSmileys = {},
                        onOptions = {}, onPreview = {}, onEvent = { _, _ -> },
                    ),
                    submitState = ArmedSubmitState(armed = false, enabled = true),
                    submitActions = ArmedSubmitActions({}, {}, {}),
                    imageActions = EditorBarImageActions(onUpload = {}),
                )
            }
        }
        compose.onNodeWithTag("editor-bar-submit").assertIsDisplayed()
        compose.onNodeWithTag("editor-bar-scroll").assertIsDisplayed()
    }
}
