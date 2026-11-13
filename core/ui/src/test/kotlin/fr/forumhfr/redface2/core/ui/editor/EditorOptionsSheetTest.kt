package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h320dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorOptionsSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lastOptionRemainsReachableInAShortWindow() {
        compose.setContent {
            RedfaceTheme {
                EditorOptionsSheet({}) {
                    Text("Options")
                    Spacer(Modifier.height(640.dp))
                    Text("Last option")
                }
            }
        }
        compose.onNodeWithText("Last option").performScrollTo().assertIsDisplayed()
    }
}
