package fr.forumhfr.redface2.core.ui.editor

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.ReadingDisplaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction as Action

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorAppBarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `ED10 context is shown only when both rendered lines fit at both scales`() {
        val scale = mutableStateOf(1f)
        val tallLines = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                val preset = if (scale.value == 1f) FontScalePreference.M else FontScalePreference.L
                RedfaceTheme(reading = ReadingDisplaySettings(fontScale = preset)) {
                    val typography = MaterialTheme.typography
                    val titleHeight = with(LocalDensity.current) { (if (tallLines.value) 32.dp else 20.dp).toSp() }
                    val contextHeight = with(LocalDensity.current) { (if (tallLines.value) 32.dp else 16.dp).toSp() }
                    val lineStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
                    MaterialTheme(typography = typography.copy(
                        titleSmall = typography.titleSmall.copy(lineHeight = titleHeight, lineHeightStyle = lineStyle,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)),
                        labelMedium = typography.labelMedium.copy(lineHeight = contextHeight,
                            lineHeightStyle = lineStyle,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    )) {
                        Column(Modifier.width(360.dp)) {
                            EditorAppBar("Répondre", "Contexte", {}, {}, Modifier.testTag("bar"))
                            // Render independently of the bar's height limit and visibility decision.
                            Text("Répondre", style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, modifier = Modifier.testTag("reference-title"))
                            Text("Contexte", style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, modifier = Modifier.testTag("reference-context"))
                        }
                    }
                }
            }
        }
        listOf(1f, 1.3f).forEach { fontScale ->
            listOf(false, true).forEach { tall ->
                compose.runOnIdle { scale.value = fontScale; tallLines.value = tall }
                val bar = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
                assertEquals(48f, bar.height.value, 0.5f)
                val title = compose.onNodeWithTag("reference-title").getUnclippedBoundsInRoot()
                val context = compose.onNodeWithTag("reference-context").getUnclippedBoundsInRoot()
                val fits = title.height + context.height <= bar.height
                assertEquals("Exercise both fitting and overflowing rendered lines at scale $fontScale", !tall, fits)
                val first = assertRenderedLineFits(EDITOR_APP_BAR_LINE_ONE, bar)
                assertEquals(title.height.value, first.height.value, 0.5f)
                if (!fits) compose.onNodeWithTag(EDITOR_APP_BAR_LINE_TWO, true).assertDoesNotExist()
                else {
                    val second = assertRenderedLineFits(EDITOR_APP_BAR_LINE_TWO, bar)
                    assertEquals(context.height.value, second.height.value, 0.5f)
                    assertTrue("Text lines must not overlap", first.bottom <= second.top)
                }
                val layout = textLayout(EDITOR_APP_BAR_LINE_ONE)
                assertEquals(fontScale, layout.layoutInput.density.fontScale, 0.001f)
                val preset = if (fontScale == 1f) FontScalePreference.M else FontScalePreference.L
                assertEquals(14f * preset.factor, layout.layoutInput.style.fontSize.value, 0.001f)
            }
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
                            Modifier.testTag("bar"),
                            firstStatus = EditorAppBarStatus(R.drawable.ic_editor_quote, "Citations", {},
                                badgeCount = 123456),
                            secondStatus = EditorAppBarStatus(R.drawable.ic_editor_restore, "Brouillon", {}),
                            onOpenHeader = {},
                            headerActionLabel = stringResource(R.string.editor_app_bar_open_topic_header))
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
    fun `ED95 each status changes its permanent polite node from absent to present and back`() {
        val first = mutableStateOf(false)
        val second = mutableStateOf(false)
        lateinit var view: View
        var clicks = 0
        val labels = listOf("3 citations, ouvrir les citations", "Brouillon retrouvé, restaurer")
        compose.setContent {
            view = LocalView.current
            RedfaceTheme {
                EditorAppBar("Répondre", "Contexte", {}, {}, Modifier.width(360.dp),
                    firstStatus = if (first.value)
                        EditorAppBarStatus(R.drawable.ic_editor_quote, labels[0], { clicks++ }, 3) else null,
                    secondStatus = if (second.value)
                        EditorAppBarStatus(R.drawable.ic_editor_restore, labels[1], { clicks++ }) else null)
            }
        }
        val live = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        // Track PR 2's permanent anchors directly, including while they have no name or action.
        val ids = compose.onAllNodes(live, useUnmergedTree = true).fetchSemanticsNodes().map { it.id }
        assertEquals(2, ids.size)
        // The second slot arrives first: inserting/removing its neighbour must never change its identity.
        listOf(false to false, false to true, true to true, true to false, false to false).forEach { (a, b) ->
            compose.runOnIdle { first.value = a; second.value = b }
            val visible = listOf(a, b)
            listOf(false, true).forEach { unmerged ->
                val nodes = compose.onAllNodes(live, useUnmergedTree = unmerged).fetchSemanticsNodes()
                assertEquals(ids, nodes.map { it.id })
                nodes.forEachIndexed { index, node ->
                    assertFalse("A status must remain a non-merging leaf", node.config.isMergingSemanticsOfDescendants)
                    if (!visible[index]) {
                        listOf(SemanticsProperties.ContentDescription, SemanticsProperties.Text,
                            SemanticsProperties.Focused, SemanticsProperties.Role, SemanticsActions.OnClick,
                            SemanticsActions.OnLongClick, SemanticsActions.RequestFocus).forEach { property ->
                            assertFalse("Absent status must not expose $property", node.config.contains(property))
                        }
                    }
                }
            }
            compose.onAllNodes(hasClickAction()).assertCountEquals(2 + visible.count { it })
            labels.forEachIndexed { index, label ->
                listOf(false, true).forEach { unmerged ->
                    compose.onAllNodes(hasContentDescription(label), useUnmergedTree = unmerged)
                        .assertCountEquals(if (visible[index]) 1 else 0)
                }
                compose.runOnIdle {
                    val info = requireNotNull(view.accessibilityNodeProvider.createAccessibilityNodeInfo(ids[index]))
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, info.liveRegion)
                    assertTrue(info.isVisibleToUser && info.isImportantForAccessibility)
                    assertEquals(if (visible[index]) label else "", info.contentDescription?.toString().orEmpty())
                    assertEquals("Absent states must not add a readable target", visible[index],
                        info.isScreenReaderFocusable)
                    assertEquals(0, info.childCount)
                    if (!visible[index]) {
                        assertFalse(info.isFocusable)
                        assertTrue(info.actionList.all { it == Action.ACTION_ACCESSIBILITY_FOCUS ||
                            it == Action.ACTION_CLEAR_ACCESSIBILITY_FOCUS })
                    }
                }
                if (visible[index]) compose.onNodeWithContentDescription(label).performClick()
            }
        }
        assertEquals(4, clicks)
        compose.runOnIdle { second.value = true }
        compose.onNodeWithContentDescription(labels[1]).performTouchInput { longClick() }
        compose.onNodeWithText(labels[1], useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodes(isRoot(), useUnmergedTree = true).assertCountEquals(2)
        compose.runOnIdle { second.value = false }
        compose.onNodeWithText(labels[1], useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodes(isRoot(), useUnmergedTree = true).assertCountEquals(1)
        assertEquals(ids, compose.onAllNodes(live, useUnmergedTree = true).fetchSemanticsNodes().map { it.id })
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        compose.onAllNodes(hasContentDescription(labels[1]), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `ED04 ED10 ED11 narrow widths after insets retain the header and scroll full size actions`() {
        val available = mutableStateOf(240.dp)
        val scale = mutableStateOf(1f)
        val hasCategory = mutableStateOf(true)
        var headers = 0
        var actions = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                val preset = if (scale.value == 1f) FontScalePreference.M else FontScalePreference.L
                RedfaceTheme(reading = ReadingDisplaySettings(fontScale = preset)) {
                    Box(Modifier.width(available.value + 24.dp)
                        .windowInsetsPadding(WindowInsets(left = 12.dp, right = 12.dp))) {
                        EditorAppBar("Nouveau sujet", "Titre et catégorie", {}, { actions++ }, Modifier.testTag("bar"),
                            firstStatus = EditorAppBarStatus(R.drawable.ic_editor_quote, "Citations", { actions++ }, 3),
                            secondStatus = EditorAppBarStatus(R.drawable.ic_editor_restore, "Brouillon", { actions++ }),
                            onOpenHeader = { headers++ }, headerActionLabel = stringResource(
                                if (hasCategory.value) R.string.editor_app_bar_open_topic_header
                                else R.string.editor_app_bar_open_topic_title))
                    }
                }
            }
        }
        listOf(1f, 1.3f).forEach { fontScale ->
            listOf(240.dp, 200.dp, 176.dp).forEach { width ->
                compose.runOnIdle {
                    available.value = width; scale.value = fontScale; hasCategory.value = width != 200.dp
                }
                val barNode = compose.onNodeWithTag("bar")
                val bar = barNode.getUnclippedBoundsInRoot()
                assertEquals(width.value, bar.width.value, 0.5f)
                assertEquals(48f, bar.height.value, 0.5f)
                val headerNode = compose.onNodeWithTag(EDITOR_APP_BAR_TITLE)
                val header = headerNode.getUnclippedBoundsInRoot()
                assertTrue(header.width >= 48.dp)
                assertEquals(48f, header.height.value, 0.5f)
                val actionLabel = if (width == 200.dp) "Modifier le titre" else "Modifier le titre et la sous-catégorie"
                headerNode.assert(hasContentDescription("$actionLabel : Nouveau sujet, Titre et catégorie"))
                    .performClick()
                val back = compose.onNodeWithContentDescription("Retour").getUnclippedBoundsInRoot()
                assertTrue(back.width >= 48.dp && back.left >= bar.left && back.right <= header.left)
                val pxPerDp = barNode.fetchSemanticsNode().boundsInRoot.width / bar.width.value
                listOf("Citations", "Brouillon", "Afficher l’aperçu").forEach { label ->
                    val target = compose.onNode(hasContentDescription(label) and
                        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    target.performScrollTo().assertIsDisplayed().performClick()
                    val bounds = target.getUnclippedBoundsInRoot()
                    assertTrue(bounds.width >= 48.dp && bounds.height >= 48.dp)
                    assertTrue(bounds.left >= header.right && bounds.right <= bar.right)
                    val clipped = target.fetchSemanticsNode().boundsInRoot
                    assertEquals("Whole target must be reachable", bounds.width.value * pxPerDp, clipped.width, 0.5f)
                    assertEquals(bounds.height.value * pxPerDp, clipped.height, 0.5f)
                    assertEquals(header, headerNode.getUnclippedBoundsInRoot())
                }
            }
        }
        assertEquals(6, headers)
        assertEquals(18, actions)
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
                    // Keep the extra line spacing: default Trim.Both can make two single lines fit.
                    val lineHeight = with(LocalDensity.current) { 32.dp.toSp() }
                    val lineStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
                    MaterialTheme(typography = typography.copy(
                        titleSmall = typography.titleSmall.copy(lineHeight = lineHeight, lineHeightStyle = lineStyle,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)),
                        labelMedium = typography.labelMedium.copy(lineHeight = lineHeight, lineHeightStyle = lineStyle,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    )) {
                        Column {
                            EditorAppBar("Nouveau MP", "Alice et Bob", { closes++ }, { previews++ },
                                Modifier.testTag("bar"), onOpenHeader = { headers++ },
                                closeEnabled = closeEnabled.value,
                                headerActionLabel = stringResource(R.string.editor_app_bar_open_message_header))
                            Text("Nouveau MP", style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, modifier = Modifier.testTag("reference-title"))
                            Text("Alice et Bob", style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, modifier = Modifier.testTag("reference-context"))
                        }
                    }
                }
            }
        }
        val bar = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithTag("reference-title").getUnclippedBoundsInRoot()
        val context = compose.onNodeWithTag("reference-context").getUnclippedBoundsInRoot()
        assertTrue("The rendered lines must exceed the app bar height", title.height + context.height > bar.height)
        assertRenderedLineFits(EDITOR_APP_BAR_LINE_ONE, bar)
        compose.onNodeWithTag(EDITOR_APP_BAR_LINE_TWO, true).assertDoesNotExist()
        compose.onNodeWithTag(EDITOR_APP_BAR_TITLE)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(hasContentDescription("Modifier les destinataires et le sujet : Nouveau MP, Alice et Bob"))
            .assert(SemanticsMatcher("MP header action") {
                it.config[SemanticsActions.OnClick].label == "Modifier les destinataires et le sujet"
            }).performClick()
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

    private fun assertRenderedLineFits(tag: String, bar: DpRect): DpRect {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        val bounds = node.getUnclippedBoundsInRoot()
        val layout = textLayout(tag)
        assertEquals(1, layout.lineCount)
        assertFalse("Text must not overflow vertically", layout.didOverflowHeight)
        assertTrue(layout.getLineTop(0) >= 0f)
        assertTrue(layout.getLineBottom(0) <= layout.size.height + 0.5f)
        assertTrue(bounds.top >= bar.top && bounds.bottom <= bar.bottom)
        assertTrue(bounds.left >= bar.left && bounds.right <= bar.right)
        val pxPerDp = layout.layoutInput.density.density
        val clipped = node.fetchSemanticsNode().boundsInRoot
        assertEquals("Rendered line must not be clipped", bounds.height.value * pxPerDp, clipped.height, 0.5f)
        assertEquals(bounds.width.value * pxPerDp, clipped.width, 0.5f)
        return bounds
    }
}
