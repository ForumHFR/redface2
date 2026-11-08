package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.ReadingDisplaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorAppBarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `ED10 actual typography decides line two rather than a font scale cutoff`() {
        val scale = mutableStateOf(1f)
        val lineHeightFactor = mutableStateOf(1f)
        var measuredHeight = 0
        var availableHeight = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                val preset = if (scale.value == 1f) FontScalePreference.M else FontScalePreference.L
                RedfaceTheme(reading = ReadingDisplaySettings(fontScale = preset)) {
                    val typography = MaterialTheme.typography
                    MaterialTheme(typography = typography.copy(
                        titleSmall = typography.titleSmall.copy(
                            lineHeight = typography.titleSmall.lineHeight * lineHeightFactor.value),
                        labelMedium = typography.labelMedium.copy(
                            lineHeight = typography.labelMedium.lineHeight * lineHeightFactor.value),
                    )) {
                        val measurer = rememberTextMeasurer()
                        measuredHeight = measurer.measure("Répondre", MaterialTheme.typography.titleSmall).size.height +
                            measurer.measure("Contexte", MaterialTheme.typography.labelMedium).size.height
                        availableHeight = with(LocalDensity.current) { 48.dp.roundToPx() }
                        EditorAppBar("Répondre", "Contexte", {}, {}, Modifier.width(360.dp).testTag("bar"))
                    }
                }
            }
        }
        // The font metrics decide visibility; neither 1.3x nor the reading preset is a cutoff.
        listOf(1f, 1.3f).forEach { fontScale ->
            val observed = mutableSetOf<Boolean>()
            listOf(0.5f, 1f, 4f).forEach { factor ->
                compose.runOnIdle { scale.value = fontScale; lineHeightFactor.value = factor }
                compose.waitForIdle()
                val visible = measuredHeight <= availableHeight
                observed.add(visible)
                if (visible) compose.onNodeWithTag(EDITOR_APP_BAR_LINE_TWO, true).assertExists()
                else compose.onNodeWithTag(EDITOR_APP_BAR_LINE_TWO, true).assertDoesNotExist()
                val layout = textLayout(EDITOR_APP_BAR_LINE_ONE)
                assertEquals(fontScale, layout.layoutInput.density.fontScale, 0.001f)
                val preset = if (fontScale == 1f) FontScalePreference.M else FontScalePreference.L
                assertEquals(14f * preset.factor, layout.layoutInput.style.fontSize.value, 0.001f)
                assertEquals(48f, compose.onNodeWithTag("bar").getUnclippedBoundsInRoot().height.value, 0.5f)
            }
            // At each system scale, exercise both branches by changing typography alone.
            assertEquals("Both visibility cases at fontScale=$fontScale", setOf(false, true), observed)
        }
    }

    @Test
    fun `ED04 ED11 title receives real remaining width after side insets and wide status badges`() {
        val inset = mutableStateOf(0.dp)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                RedfaceTheme(reading = ReadingDisplaySettings(fontScale = FontScalePreference.L)) {
                    val sides = WindowInsets(left = inset.value, right = inset.value)
                    Box(Modifier.width(360.dp).windowInsetsPadding(sides)) {
                        EditorAppBar("Nouveau sujet au titre suffisamment long pour être tronqué", "Contexte", {}, {},
                            Modifier.testTag("bar"), statuses = listOf(
                                EditorAppBarStatus(R.drawable.ic_editor_quote, "Citations", {}, badgeCount = 123456),
                                EditorAppBarStatus(R.drawable.ic_editor_restore, "Brouillon", {}),
                            ), onOpenHeader = {})
                    }
                }
            }
        }
        listOf(0.dp, 12.dp).forEach { side ->
            compose.runOnIdle { inset.value = side }
            val bar = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
            assertEquals(360f - 2 * side.value, bar.width.value, 0.5f)
            val targets = listOf("Retour", "Citations", "Brouillon", "Afficher l’aperçu").map { label ->
                compose.onNode(hasContentDescription(label) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .getUnclippedBoundsInRoot().also {
                        assertTrue(it.width >= 48.dp)
                        assertEquals(48f, it.height.value, 0.5f)
                        assertTrue(it.left >= bar.left && it.right <= bar.right)
                    }
            }
            val title = compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).getUnclippedBoundsInRoot()
            val reserved = targets.sumOf { it.width.value.toDouble() }.toFloat()
            assertEquals(bar.width.value - 8f - reserved, title.width.value, 0.5f)
            assertTrue(title.width >= 48.dp)
            assertEquals(48f, title.height.value, 0.5f)
            val text = textLayout(EDITOR_APP_BAR_LINE_ONE)
            assertEquals(title.width.value - 24f, text.size.width / text.layoutInput.density.density, 0.5f)
            assertEquals(1, text.lineCount)
            assertTrue(text.isLineEllipsized(0))
            assertTrue(targets[0].right <= title.left)
            assertTrue(title.right <= targets[1].left)
            assertTrue(targets[1].right <= targets[2].left && targets[2].right <= targets[3].left)
        }
    }

    @Test
    fun `ED10 hidden context keeps the header button and close uses only the guarded callback`() {
        val closeEnabled = mutableStateOf(true)
        var closes = 0
        var headers = 0
        var previews = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                RedfaceTheme(reading = ReadingDisplaySettings(fontScale = FontScalePreference.L)) {
                    val typography = MaterialTheme.typography
                    // Two 32 dp lines cannot share the 48 dp app bar, even if 1.3x + L would fit.
                    val lineHeight = with(LocalDensity.current) { 32.dp.toSp() }
                    MaterialTheme(typography = typography.copy(
                        titleSmall = typography.titleSmall.copy(lineHeight = lineHeight),
                        labelMedium = typography.labelMedium.copy(lineHeight = lineHeight),
                    )) {
                        EditorAppBar("Nouveau MP", "Alice et Bob", { closes++ }, { previews++ },
                            onOpenHeader = { headers++ }, closeEnabled = closeEnabled.value)
                    }
                }
            }
        }
        compose.onNodeWithTag(EDITOR_APP_BAR_LINE_TWO, true).assertDoesNotExist()
        compose.onNodeWithTag(EDITOR_APP_BAR_TITLE)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(hasContentDescription("Modifier l’en-tête : Nouveau MP, Alice et Bob")).performClick()
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithContentDescription("Afficher l’aperçu").performClick()
        assertEquals(1, headers)
        assertEquals(1, closes)
        assertEquals(1, previews)
        compose.runOnIdle { closeEnabled.value = false }
        compose.onNodeWithContentDescription("Retour").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, closes)
    }

    @Test
    fun `ED10 context ellipsizes on one line and ED05 compact has no app bar`() {
        val compact = mutableStateOf(false)
        val context = mutableStateOf("Un contexte de rédaction très long ".repeat(20))
        compose.setContent {
            RedfaceTheme {
                EditorAppBar("Modifier", context.value, {}, {}, Modifier.width(240.dp), compactHeight = compact.value)
            }
        }
        compose.onNodeWithTag(EDITOR_APP_BAR_LINE_ONE, true).assertExists()
        val layout = textLayout(EDITOR_APP_BAR_LINE_TWO)
        assertEquals(1, layout.lineCount)
        assertTrue(layout.multiParagraph.intrinsics.maxIntrinsicWidth > layout.layoutInput.constraints.maxWidth)
        assertTrue(layout.isLineEllipsized(0))
        compose.runOnIdle { context.value = "" }
        compose.onNodeWithTag(EDITOR_APP_BAR_LINE_TWO, true).assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        compose.runOnIdle { compact.value = true }
        compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        assertTrue(requireNotNull(action).invoke(results))
        return results.single()
    }
}
