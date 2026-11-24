package fr.forumhfr.redface2.feature.flags

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshPolicy
import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshStrategy
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h851dp")
class FlagsRefreshPolicyDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `each option owns its description and a full selectable touch target`() {
        mount()
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        val options = listOf(
            R.string.diagnostics_flags_baseline to R.string.diagnostics_flags_baseline_help,
            R.string.diagnostics_flags_legacy to R.string.diagnostics_flags_legacy_help,
            R.string.diagnostics_flags_global_only to R.string.diagnostics_flags_global_only_help,
            R.string.diagnostics_flags_global_first to R.string.diagnostics_flags_global_first_help,
        )
        compose.onAllNodes(radio).assertCountEquals(4)
        options.forEach { (label, description) ->
            compose.onAllNodes(hasText(text(description))).assertCountEquals(1)
            compose.onNode(radio and hasText(text(label)) and hasText(text(description)))
                .performScrollTo()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
                .assertIsSelected()
        }
    }

    @Test
    fun `dialog shows fifteen as the default concurrency`() {
        mount()
        compose.onNodeWithText("15 requêtes simultanées", substring = true).assertIsDisplayed()
        compose.onNodeWithText("N choisi : 15").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("N effectif : 15").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `legacy disables N and restores the retained value when leaving legacy`() {
        val initial = FlagsRefreshPolicy(concurrency = 7, applicationRetry = false)
        var saved: FlagsRefreshPolicy? = null
        mount(initial, onSave = { saved = it })
        choose(R.string.diagnostics_flags_legacy)
        compose.onNodeWithText("N choisi : 7").performScrollTo().assertIsNotEnabled()
        decrease().assertIsNotEnabled()
        increase().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.diagnostics_flags_n_ignored))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.diagnostics_flags_unbounded)).assertExists()
        choose(R.string.diagnostics_flags_baseline)
        compose.onNodeWithText("N choisi : 7").performScrollTo().assertIsEnabled()
        decrease().assertIsEnabled()
        increase().assertIsEnabled()
        compose.onNodeWithText(text(R.string.diagnostics_flags_n_ignored)).assertDoesNotExist()
        choose(R.string.diagnostics_flags_legacy)
        assertNull(saved)
        compose.onNodeWithText(text(R.string.diagnostics_flags_apply)).performClick()
        assertEquals(initial.copy(strategy = FlagsRefreshStrategy.LEGACY), saved)
    }

    @Test
    fun `N at twenty keeps accessible icon controls and its upper bound in dark theme`() {
        var saved: FlagsRefreshPolicy? = null
        mount(FlagsRefreshPolicy(concurrency = 20), darkTheme = true, onSave = { saved = it })
        compose.onNodeWithText("N choisi : 20").performScrollTo().assertIsDisplayed()
        increase().assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        decrease().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("N choisi : 19").assertExists()
        increase().assertIsEnabled().performClick()
        increase().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.diagnostics_flags_apply)).performClick()
        assertEquals(FlagsRefreshPolicy(concurrency = 20), saved)
    }

    @Test
    fun `N at one keeps its lower bound and cancel leaves the policy unsaved`() {
        var saved: FlagsRefreshPolicy? = null
        var dismissed = false
        mount(FlagsRefreshPolicy(concurrency = 1), onDismiss = { dismissed = true }, onSave = { saved = it })
        compose.onNodeWithText("N choisi : 1").performScrollTo()
        decrease().assertIsNotEnabled()
        increase().assertIsEnabled().performClick()
        decrease().assertIsEnabled()
        compose.onNodeWithText("N choisi : 2").assertExists()
        compose.onNodeWithText(text(R.string.diagnostics_flags_cancel)).performClick()
        assertEquals(true, dismissed)
        assertNull(saved)
    }

    private fun choose(label: Int) {
        compose.onNodeWithText(text(label)).performScrollTo().performClick().assertIsSelected()
    }

    private fun decrease() = compose.onNodeWithContentDescription(text(R.string.diagnostics_flags_decrease))

    private fun increase() = compose.onNodeWithContentDescription(text(R.string.diagnostics_flags_increase))

    private fun text(resource: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(resource)

    private fun mount(
        policy: FlagsRefreshPolicy = FlagsRefreshPolicy(),
        darkTheme: Boolean = false,
        onDismiss: () -> Unit = {},
        onSave: (FlagsRefreshPolicy) -> Unit = {},
    ) {
        compose.setContent {
            RedfaceTheme(darkTheme = darkTheme, dynamicColor = false) {
                FlagsRefreshPolicyDialog(policy, saveFailed = false, onDismiss = onDismiss, onSave = onSave)
            }
        }
    }
}
