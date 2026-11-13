package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** #1417: one anchor, an « Options | Formats » switch, a body of stable height, Back in one gesture. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h320dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorOptionsSheetTest {
    @get:Rule val compose = createComposeRule()
    private var tab by mutableStateOf(EditorOptionsTab.Options)
    private var sheetOpen by mutableStateOf(true)
    private var extraOptionsHeight by mutableStateOf(0)
    private var dismissals = 0
    // As the hosts do: a dismissal removes the sheet from the composition.
    private val dismiss: () -> Unit = { dismissals++; sheetOpen = false }
    private val formatted = mutableListOf<BbcodeAction>()
    private val atTop = SemanticsMatcher("scrolled to the top") {
        it.config[SemanticsProperties.VerticalScrollAxisRange].value() == 0f
    }

    private fun setSheet(optionsHeight: Int = 0) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                if (sheetOpen) {
                    EditorOptionsSheet(dismiss, EditorOptionsFormats(tab, true, { tab = it }, { formatted += it })) {
                        Column(Modifier.semantics { paneTitle = "Options HFR" }) {
                            Text("Première option")
                            Spacer(Modifier.height((optionsHeight + extraOptionsHeight).dp))
                            Text("Dernière option")
                        }
                    }
                }
            }
        }
    }

    private fun sheetBack() {
        compose.runOnIdle {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    private fun tabNode(tab: EditorOptionsTab) = compose.onNodeWithTag(EDITOR_OPTIONS_TAB_TAG_PREFIX + tab.name)
    private fun body() = compose.onNodeWithTag("editor-options-sheet-scroll")

    @Test fun lastOptionRemainsReachableInAShortWindow() {
        setSheet(optionsHeight = 640)
        tabNode(EditorOptionsTab.Options).assertIsDisplayed()
        compose.onNodeWithText("Dernière option").performScrollTo().assertIsDisplayed()
        // The switch stays above the scrolling body.
        tabNode(EditorOptionsTab.Formats).assertIsDisplayed()
    }

    // One resting anchor: the sheet opens at its content height, with no half-height anchor to cut a row.
    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun contentTallerThanHalfTheWindowOpensWithItsLastRowVisible() {
        setSheet(optionsHeight = 300)
        compose.onNodeWithText("Première option").assertIsDisplayed()
        compose.onNodeWithText("Dernière option").assertIsDisplayed()
        body().assert(atTop)
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun switchExposesTwoTabsOf48DpAndSelectsTheTappedOne() {
        setSheet()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertCountEquals(1)
        compose.onAllNodes(isSelectable()).assertCountEquals(2)
        listOf(EditorOptionsTab.Options to "Options", EditorOptionsTab.Formats to "Formats").forEach { (tab, label) ->
            tabNode(tab).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assert(hasText(label)).assertHeightIsEqualTo(48.dp)
        }
        tabNode(EditorOptionsTab.Options).assertIsSelected()
        // The tab is labelled by text; only the action bar's button uses contentDescription = "Options".
        compose.onAllNodes(hasContentDescription("Options")).assertCountEquals(0)
        tabNode(EditorOptionsTab.Formats).assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(EditorOptionsTab.Formats, tab) }
        tabNode(EditorOptionsTab.Formats).assertIsSelected()
        tabNode(EditorOptionsTab.Options).assertIsNotSelected()
        compose.onNodeWithText("Première option").assertDoesNotExist()
        compose.onNodeWithTag(EDITOR_FORMATS_LIST_TAG).assertExists()
        tabNode(EditorOptionsTab.Options).performClick()
        compose.onNodeWithText("Première option").assertIsDisplayed()
        compose.onNodeWithTag(EDITOR_FORMATS_LIST_TAG).assertDoesNotExist()
    }

    // The formats list is taller than the options: the body keeps the options height and scrolls.
    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun sheetKeepsItsHeightBetweenTheTwoTabs() {
        setSheet(optionsHeight = 200)
        val switchTop = tabNode(EditorOptionsTab.Options).getUnclippedBoundsInRoot().top
        val bodyBounds = body().getUnclippedBoundsInRoot()
        tabNode(EditorOptionsTab.Formats).performClick()
        assertEquals(switchTop, tabNode(EditorOptionsTab.Options).getUnclippedBoundsInRoot().top)
        assertEquals(bodyBounds, body().getUnclippedBoundsInRoot())
        tabNode(EditorOptionsTab.Options).performClick()
        assertEquals(switchTop, tabNode(EditorOptionsTab.Options).getUnclippedBoundsInRoot().top)
        assertEquals(bodyBounds, body().getUnclippedBoundsInRoot())
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun firstFormatsFrameKeepsTheSheetAndBodyBounds() {
        setSheet(optionsHeight = 200)
        val sheetBounds = compose.onNodeWithTag(EDITOR_OPTIONS_SHEET_TAG).getUnclippedBoundsInRoot()
        val bodyBounds = body().getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        tabNode(EditorOptionsTab.Formats).performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(sheetBounds, compose.onNodeWithTag(EDITOR_OPTIONS_SHEET_TAG).getUnclippedBoundsInRoot())
        assertEquals(bodyBounds, body().getUnclippedBoundsInRoot())
        compose.mainClock.autoAdvance = true
    }

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun recreatedOnFormatsUsesTheCurrentOptionsHeightWithoutAFallback() {
        tab = EditorOptionsTab.Formats
        setSheet(optionsHeight = 70)
        val formatsBounds = body().getUnclippedBoundsInRoot()
        tabNode(EditorOptionsTab.Options).performClick()
        assertEquals(formatsBounds, body().getUnclippedBoundsInRoot())
        compose.onNodeWithText("Dernière option").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun formatsRemeasuresOptionsWhenTheirContentChanges() {
        setSheet(optionsHeight = 70)
        tabNode(EditorOptionsTab.Formats).performClick()
        val initial = body().getUnclippedBoundsInRoot()
        compose.runOnIdle { extraOptionsHeight = 100 }
        val updated = body().getUnclippedBoundsInRoot()
        assertEquals(initial.bottom - initial.top + 100.dp, updated.bottom - updated.top)
        tabNode(EditorOptionsTab.Options).performClick()
        assertEquals(updated, body().getUnclippedBoundsInRoot())
    }

    @Test fun formatsDoesNotExposeTheMeasuringOptionsPaneOrItsControls() {
        setSheet(optionsHeight = 70)
        val optionsPane = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Options HFR")
        compose.onNode(optionsPane).assertExists()
        tabNode(EditorOptionsTab.Formats).performClick()
        compose.onNode(optionsPane).assertDoesNotExist()
        compose.onNodeWithText("Première option").assertDoesNotExist()
        tabNode(EditorOptionsTab.Options).performClick()
        compose.onNode(optionsPane).assertExists()
    }

    @Test fun backClosesTheSheetInOneGestureFromEitherTab() {
        setSheet()
        EditorOptionsTab.entries.forEachIndexed { index, opened ->
            compose.runOnIdle { tab = opened; sheetOpen = true }
            tabNode(opened).assertIsSelected()
            sheetBack()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(index + 1, dismissals)
                // Back never changes tab: the host resets it when it closes the surface.
                assertEquals(opened, tab)
            }
        }
    }

    @Test fun lastFormatRemainsReachableInAShortWindow() {
        tab = EditorOptionsTab.Formats
        setSheet()
        compose.onNodeWithTag(EDITOR_FORMATS_OPTION_TAG_PREFIX + "email").performScrollTo().assertIsDisplayed()
            .performClick()
        tabNode(EditorOptionsTab.Options).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf<BbcodeAction>(BbcodeAction.Email), formatted) }
    }

    @Test fun eachTabStartsAtTheTop() {
        setSheet(optionsHeight = 640)
        compose.onNodeWithText("Dernière option").performScrollTo()
        tabNode(EditorOptionsTab.Formats).performClick()
        body().assert(atTop)
        compose.onNodeWithTag(EDITOR_FORMATS_OPTION_TAG_PREFIX + "email").performScrollTo()
        tabNode(EditorOptionsTab.Options).performClick()
        body().assert(atTop)
        compose.onNodeWithText("Première option").assertIsDisplayed()
    }

    @Test fun withoutFormatsTheSheetHostsItsContentWithoutASwitch() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                if (sheetOpen) EditorOptionsSheet(dismiss) { Text("Contenu") }
            }
        }
        compose.onNodeWithText("Contenu").assertIsDisplayed()
        compose.onAllNodes(isSelectable()).assertCountEquals(0)
        sheetBack()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }
}
