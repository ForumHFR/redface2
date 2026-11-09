package fr.forumhfr.redface2.core.ui.editor

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextOverflow
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
import org.junit.Assert.assertThrows
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
            val targets = listOf("Retour", "Afficher l’aperçu", "Citations", "Brouillon").map { label ->
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
    fun `ED10 badge and 24 dp glyph fit the 48 dp bar through 2x L without text clipping`() {
        val scale = mutableStateOf(1f)
        val count = mutableStateOf(12)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                RedfaceTheme(reading = ReadingDisplaySettings(fontScale = FontScalePreference.L)) {
                    EditorAppBar("Répondre", "Contexte", {}, {}, Modifier.width(360.dp).testTag("bar"),
                        firstStatus = EditorAppBarStatus(R.drawable.ic_editor_quote, "Citations", {}, count.value))
                }
            }
        }
        listOf(1f, 1.3f, 1.5f, 2f).forEach { fontScale ->
            listOf(12, 123, 123456).forEach { badgeCount ->
                compose.runOnIdle { scale.value = fontScale; count.value = badgeCount }
                assertStatusFits(fontScale, badgeCount)
            }
        }
    }

    @Test
    fun `ED10 geometry oracle accepts loose constraints but rejects clipped digits and line height`() {
        compose.setContent {
            RedfaceTheme {
                Column(Modifier.width(160.dp).testTag("oracle")) {
                    Text("12", style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        modifier = Modifier.widthIn(max = 120.dp).testTag("loose"))
                    Text("Un titre beaucoup trop long", style = MaterialTheme.typography.titleSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.width(64.dp).testTag("ellipsis"))
                    Text("123456", style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        modifier = Modifier.width(8.dp).testTag("narrow"))
                    Text("12", style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        modifier = Modifier.height(1.dp).testTag("short"))
                }
            }
        }
        val envelope = compose.onNodeWithTag("oracle").getUnclippedBoundsInRoot()
        assertRenderedLineFits("loose", envelope)
        assertDigitsFit(textLayout("loose"), "12")
        assertRenderedLineFits("ellipsis", envelope)
        assertTrue(textLayout("ellipsis").isLineEllipsized(0))
        val narrow = textLayout("narrow")
        assertThrows(AssertionError::class.java) { assertDigitsFit(narrow, "123456") }
        assertThrows(AssertionError::class.java) { assertRenderedLineFits("short", envelope) }
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
                listOf("Afficher l’aperçu", "Citations", "Brouillon").forEach { label ->
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
    fun `ED25 preview is fully visible at 200 dp with two statuses before any scroll`() {
        var previews = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                RedfaceTheme(reading = ReadingDisplaySettings(fontScale = FontScalePreference.L)) {
                    EditorAppBar("Nouveau sujet", "Titre et catégorie", {}, { previews++ },
                        Modifier.width(200.dp).testTag("bar"),
                        firstStatus = EditorAppBarStatus(R.drawable.ic_editor_quote, "Citations", {}, 3),
                        secondStatus = EditorAppBarStatus(R.drawable.ic_editor_restore, "Brouillon", {}),
                        onOpenHeader = {}, headerActionLabel = "Modifier le titre")
                }
            }
        }
        val barNode = compose.onNodeWithTag("bar")
        val bar = barNode.getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).getUnclippedBoundsInRoot()
        val preview = compose.onNodeWithContentDescription("Afficher l’aperçu").assertIsDisplayed()
        val bounds = preview.getUnclippedBoundsInRoot()
        assertEquals(48f, bounds.width.value, 0.5f)
        assertEquals(48f, bounds.height.value, 0.5f)
        assertTrue(bounds.left >= header.right && bounds.right <= bar.right)
        val pixelsPerDp = barNode.fetchSemanticsNode().boundsInRoot.width / bar.width.value
        assertEquals(bounds.width.value * pixelsPerDp, preview.fetchSemanticsNode().boundsInRoot.width, 0.5f)
        preview.performTouchInput { click() }
        assertEquals(1, previews)
    }

    @Test
    fun `ED10 title avoids a lone ellipsis and context is hidden unless readable and fitting`() {
        val scale = mutableStateOf(1.3f)
        val context = mutableStateOf("W".repeat(20))
        var headers = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                RedfaceTheme(reading = ReadingDisplaySettings(fontScale = FontScalePreference.L)) {
                    Column {
                        EditorAppBar("Nouveau sujet", context.value, {}, {}, Modifier.width(200.dp).testTag("bar"),
                            firstStatus = EditorAppBarStatus(R.drawable.ic_editor_quote, "Citations", {}, 3),
                            secondStatus = EditorAppBarStatus(R.drawable.ic_editor_restore, "Brouillon", {}),
                            onOpenHeader = { headers++ }, headerActionLabel = "Modifier le titre")
                        Text("Nouveau sujet", style = MaterialTheme.typography.titleSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(24.dp).testTag("reference-title"))
                        Text(context.value, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(24.dp).testTag("reference-context"))
                    }
                }
            }
        }
        listOf(1.3f, 2f).forEach { fontScale ->
            compose.runOnIdle { scale.value = fontScale; context.value = "W".repeat(20) }
            val bar = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
            val title = assertRenderedLineFits(EDITOR_APP_BAR_LINE_ONE, bar)
            assertEquals(24f, title.width.value, 0.5f)
            val referenceTitle = textLayout("reference-title")
            assertTrue(referenceTitle.isLineEllipsized(0))
            assertEquals(0, referenceTitle.getLineEnd(0, visibleEnd = true))
            val renderedTitle = textLayout(EDITOR_APP_BAR_LINE_ONE)
            val onlyEllipsis = renderedTitle.isLineEllipsized(0) &&
                renderedTitle.getLineEnd(0, visibleEnd = true) == 0
            assertFalse("The title must never be an ellipsis alone", onlyEllipsis)
            val referenceContext = textLayout("reference-context")
            assertTrue(referenceContext.isLineEllipsized(0))
            assertEquals(0, referenceContext.getLineEnd(0, visibleEnd = true))
            val contextHeight = compose.onNodeWithTag("reference-context").getUnclippedBoundsInRoot().height
            val linesFit = title.height + contextHeight <= bar.height
            assertEquals("Exercise width and height independently", fontScale == 1.3f, linesFit)
            compose.onNodeWithTag(EDITOR_APP_BAR_LINE_TWO, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).assertIsDisplayed()
                .assert(hasContentDescription("Modifier le titre : Nouveau sujet, ${context.value}"))
                .performClick()
            compose.runOnIdle { context.value = "I" }
            if (linesFit) {
                assertRenderedLineFits(EDITOR_APP_BAR_LINE_TWO, bar)
                assertEquals(1, textLayout(EDITOR_APP_BAR_LINE_TWO).getLineEnd(0, visibleEnd = true))
            } else compose.onNodeWithTag(EDITOR_APP_BAR_LINE_TWO, useUnmergedTree = true).assertDoesNotExist()
        }
        assertEquals(2, headers)
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
            .assert(SemanticsMatcher("Header action has no duplicate label") {
                it.config[SemanticsActions.OnClick].label == null
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

    private fun assertStatusFits(fontScale: Float, badgeCount: Int) {
        val barNode = compose.onNodeWithTag("bar")
        val bar = barNode.getUnclippedBoundsInRoot()
        assertEquals(48f, bar.height.value, 0.5f)
        val pixelsPerDp = barNode.fetchSemanticsNode().boundsInRoot.height / bar.height.value
        val glyph = assertStatusPartFits(EDITOR_STATUS_GLYPH_TAG, bar, pixelsPerDp)
        assertEquals(24f, glyph.height.value, 0.5f)
        assertEquals(24f, glyph.width.value, 0.5f)
        assertStatusPartFits(EDITOR_STATUS_BADGE_TAG, bar, pixelsPerDp)
        assertRenderedLineFits(EDITOR_STATUS_BADGE_TEXT_TAG, bar)
        val text = textLayout(EDITOR_STATUS_BADGE_TEXT_TAG)
        assertEquals(fontScale, text.layoutInput.density.fontScale, 0.001f)
        assertEquals(11f * FontScalePreference.L.factor, text.layoutInput.style.fontSize.value, 0.001f)
        assertDigitsFit(text, badgeCount.toString())
    }

    private fun assertDigitsFit(text: TextLayoutResult, digits: String) {
        assertEquals(1, text.lineCount)
        assertEquals(digits, text.layoutInput.text.text)
        assertEquals("Every digit must be on the line", digits.length, text.getLineEnd(0, visibleEnd = true))
        assertFalse("Digits must not be ellipsized", text.isLineEllipsized(0))
        // Simple Text's GetTextLayoutResult rebuilds MultiParagraph at the incoming maxWidth, which
        // can exceed its measured size (ParagraphLayoutCache). didOverflowWidth then counts empty slack.
        // Check the full untruncated text's intrinsic width and actual line edges instead.
        assertTrue("All digits must fit", text.multiParagraph.intrinsics.maxIntrinsicWidth <= text.size.width)
        assertTrue(text.getLineLeft(0) >= 0f)
        assertTrue(text.getLineRight(0) <= text.size.width)
    }

    private fun assertStatusPartFits(tag: String, bar: DpRect, pixelsPerDp: Float): DpRect {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue(bounds.top >= bar.top && bounds.bottom <= bar.bottom)
        assertTrue(bounds.left >= bar.left && bounds.right <= bar.right)
        val clipped = node.fetchSemanticsNode().boundsInRoot
        assertEquals("$tag must not be clipped", bounds.height.value * pixelsPerDp, clipped.height, 0.5f)
        assertEquals(bounds.width.value * pixelsPerDp, clipped.width, 0.5f)
        return bounds
    }

    private fun assertRenderedLineFits(tag: String, bar: DpRect): DpRect {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        val bounds = node.getUnclippedBoundsInRoot()
        val layout = textLayout(tag)
        assertEquals(1, layout.lineCount)
        // didOverflowHeight also includes didExceedMaxLines after horizontal ellipsizing.
        // Paragraph/line geometry tests vertical clipping without rejecting intentional ellipses.
        assertTrue("Text height must fit", layout.multiParagraph.height <= layout.size.height)
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
