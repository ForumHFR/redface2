package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
class EditorOptionsTest {
    @get:Rule val compose = createComposeRule()
    private val labels = EditorOptionsLabels(
        "Options", "Signature", "Désactiver les smileys", "Notification par e-mail",
    )
    private val optionLabels = listOf(labels.signature, labels.smileyDisabled, labels.emailNotification)
    private val values = List(3) { mutableStateOf(it == 1) }
    private val enabled = mutableStateOf(true)
    private val changes = mutableListOf<Pair<String, Boolean>>()

    @Test fun eachOptionExposesOneNamedSwitchAtLeast48Dp() {
        setContent()
        // Both trees must contain exactly one toggle per option, with no nested interactive Switch.
        listOf(false, true).forEach { unmerged ->
            compose.onAllNodes(isToggleable(), useUnmergedTree = unmerged).assertCountEquals(optionLabels.size)
        }
        optionLabels.forEachIndexed { index, label ->
            val matcher = isToggleable() and hasText(label)
            compose.onAllNodes(matcher).assertCountEquals(1)
            val option = compose.onNode(matcher).assertIsEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            if (index == 1) option.assertIsOn() else option.assertIsOff()
        }
    }

    @Test fun tappingLabelsTogglesEachOptionOnce() {
        setContent()
        val expected = mutableListOf<Pair<String, Boolean>>()
        optionLabels.forEachIndexed { index, label ->
            // Touch the Text itself, away from the switch at the right edge of the row.
            compose.onNodeWithText(label, useUnmergedTree = true).performTouchInput { click() }
            expected += label to (index != 1)
            compose.runOnIdle { assertEquals(expected, changes) }
            val option = compose.onNode(isToggleable() and hasText(label))
            if (index == 1) option.assertIsOff() else option.assertIsOn()
        }
    }

    @Test fun disabledOptionsRejectLabelTaps() {
        setContent()
        compose.runOnIdle { enabled.value = false }
        optionLabels.forEachIndexed { index, label ->
            val option = compose.onNode(isToggleable() and hasText(label)).assertIsNotEnabled()
            compose.onNodeWithText(label, useUnmergedTree = true).performTouchInput { click() }
            if (index == 1) option.assertIsOn() else option.assertIsOff()
        }
        compose.runOnIdle { assertTrue(changes.isEmpty()) }
    }

    @Test fun settingsAreAbsentWithoutNavigationCallback() {
        setContent()
        compose.onNodeWithText("Réglages de l’éditeur").assertDoesNotExist()
    }

    @Test fun settingsExposeOneButtonWithItsDescriptionAndMinimumHeight() {
        var opens = 0
        setContent { opens++ }
        compose.onAllNodes(hasClickAction() and hasText("Réglages de l’éditeur")).assertCountEquals(1)
        compose.onNodeWithText("Réglages de l’éditeur")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(hasText("Raccourci de la barre, confirmation avant envoi"))
            .assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun disabledSettingsRejectTaps() {
        var opens = 0
        setContent { opens++ }
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("Réglages de l’éditeur").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, opens) }
    }

    private fun setContent(onOpenSettings: (() -> Unit)? = null) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                EditorOptions(
                    signatureEnabled = values[0].value,
                    smileyDisabled = values[1].value,
                    emailNotificationEnabled = values[2].value,
                    enabled = enabled.value,
                    onSignatureChanged = { changed(0, it) },
                    onSmileyDisabledChanged = { changed(1, it) },
                    onEmailNotificationChanged = { changed(2, it) },
                    labels = labels,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }

    private fun changed(index: Int, value: Boolean) {
        values[index].value = value
        changes += optionLabels[index] to value
    }
}
