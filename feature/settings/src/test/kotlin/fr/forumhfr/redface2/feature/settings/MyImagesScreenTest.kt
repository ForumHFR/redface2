package fr.forumhfr.redface2.feature.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-xxhdpi")
class MyImagesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `account menu action is rendered in the top bar`() {
        var opens = 0
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                MyImagesContent(
                    state = MyImagesUiState(mode = MyImagesUiState.Mode.Content(emptyList())),
                    onIntent = {},
                    onBack = {},
                    topBarActions = { TextButton(onClick = { opens++ }) { Text("Compte") } },
                )
            }
        }
        compose.onNodeWithText("Compte").assertIsDisplayed().performClick()
        assertEquals(1, opens)
    }
}
