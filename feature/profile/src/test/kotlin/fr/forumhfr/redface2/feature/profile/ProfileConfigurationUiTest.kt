package fr.forumhfr.redface2.feature.profile

import android.widget.Magnifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import fr.forumhfr.redface2.core.model.UserProfile
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h760dp-xxhdpi",
    shadows = [NoopProfileShadowMagnifier::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ProfileConfigurationUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val profile = UserProfile(
        userId = 54596,
        pseudo = "XaTriX",
        avatarUrl = null,
        registeredAt = "12/06/2002",
        postCount = 213400,
        location = "Katowice (PL)",
        signatureText = null,
    )

    private var configurationLines = XATRIX_LINES

    @Before
    fun resetFontScale() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test
    fun `empty configuration takes no section space in the sheet`() {
        showSheet(ProfileUiState.Configuration.Loaded(emptyList()))

        assertTextAbsent(TITLE)
        assertTextAbsent("Tout afficher")
        assertActionsVisible()
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test
    fun `loading an empty configuration never changes the card height or action positions`() {
        val state = mutableStateOf(loadedState(ProfileUiState.Configuration.Loading))
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ProfilePreviewSheetContent(state.value, {}, {}, {})
            }
        }

        assertTextAbsent(TITLE)
        assertTextAbsent("Chargement…")
        val loadingBounds = sheetBounds()
        val loadingActions = actionBounds()
        val before = measurements()
        assertActionsVisible()

        // Include a slow response: no loading placeholder should change the card's geometry.
        compose.mainClock.advanceTimeBy(500)
        assertEquals(message("a slow empty load must preserve the sheet; before=$before"), loadingBounds, sheetBounds())
        compose.runOnIdle { state.value = loadedState(ProfileUiState.Configuration.Loaded(emptyList())) }

        assertTextAbsent(TITLE)
        assertEquals(
            message("an empty response must preserve the sheet height; before=$before"),
            loadingBounds.height, sheetBounds().height, 1f,
        )
        assertEquals(
            message("an empty response must preserve the sheet position; before=$before"),
            loadingBounds.top, sheetBounds().top, 1f,
        )
        assertEquals(
            message("an empty response must preserve the actions; before=$before"), loadingActions, actionBounds(),
        )
        assertActionsVisible()
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test
    fun `configuration follows fully visible actions at opening and after expansion`() {
        showSheet(ProfileUiState.Configuration.Loaded(XATRIX_LINES))
        assertActionsVisible()
        assertConfigurationAfterActions()

        expandSheet()

        assertActionsVisible()
        assertConfigurationAfterActions()
    }

    @Config(qualifiers = "w760dp-h360dp-land-xxhdpi")
    @Test
    fun `landscape expansion shows both actions and nonzero profile information`() {
        showSheet(ProfileUiState.Configuration.Loaded(XATRIX_LINES))
        expandSheet()

        assertActionsVisible()
        assertVisible(compose.onNodeWithText("Katowice (PL)"), "profile location")
        assertVisible(compose.onNodeWithText("213400"), "post count")
        unfoldConfiguration()
        assertLastLineReachable()
        assertActionsReachable()
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test
    fun `twelve hardware lines fold to four rendered lines and expand accessibly`() {
        showSheet(ProfileUiState.Configuration.Loaded(XATRIX_LINES))
        assertActionsVisible()
        expandSheet()

        val heading = compose.onNodeWithText(TITLE).performScrollTo()
        assertVisible(heading, "configuration heading")
        heading.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)) {
            message("the configuration title must be a heading")
        }
        assertEquals(message("the preview must have four rendered lines"), 4, configurationLayout().lineCount)
        assertTrue(message("the folded configuration must be ellipsized"), configurationLayout().hasVisualOverflow)

        unfoldConfiguration()

        assertTextAbsent("Tout afficher")
        assertTrue(message("all twelve hardware lines must be laid out"), configurationLayout().lineCount >= 12)
        assertFalse(
            message("the expanded configuration must not be ellipsized"), configurationLayout().hasVisualOverflow,
        )
        // Text semantics contain every line for TalkBack, including the last network reference.
        compose.onNodeWithText(XATRIX_LINES.joinToString("\n")).assertExists(message("TalkBack must have every line"))
        assertLastLineReachable()
        assertActionsReachable()
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test
    fun `long expanded configuration reaches its last line and actions remain reachable`() {
        showSheet(ProfileUiState.Configuration.Loaded(SCROLL_LINES))
        assertActionsVisible()
        expandSheet()

        unfoldConfiguration()

        assertLastLineReachable()
        assertActionsReachable()
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test
    fun `double font scale reaches the last line and both actions in the dialog`() {
        showSheet(ProfileUiState.Configuration.Loaded(XATRIX_LINES), fontScale = 2f)

        // RuntimeEnvironment scales the dialog owner too; verify the density of the actual Text.
        assertEquals(
            message("the dialog Text must use fontScale 2"),
            2f, configurationLayout().layoutInput.density.fontScale, 0f,
        )
        assertActionsVisible()
        expandSheet()
        assertActionsVisible()
        unfoldConfiguration()

        assertFalse(
            message("large text must not be ellipsized after expansion"), configurationLayout().hasVisualOverflow,
        )
        assertLastLineReachable()
        assertActionsReachable()
    }

    @Config(qualifiers = "w760dp-h360dp-land-xxhdpi")
    @Test
    fun `landscape at double font scale keeps each action and the last configuration line reachable`() {
        showSheet(ProfileUiState.Configuration.Loaded(XATRIX_LINES), fontScale = 2f)
        assertEquals(
            message("the landscape Text must use fontScale 2"),
            2f, configurationLayout().layoutInput.density.fontScale, 0f,
        )
        expandSheet()
        assertActionsReachable()

        unfoldConfiguration()

        assertLastLineReachable()
        assertActionsReachable()
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test
    fun `long press and drag at the bottom and top of configuration never dismisses or moves the sheet`() {
        var dismissals = 0
        showSheet(ProfileUiState.Configuration.Loaded(SCROLL_LINES), onDismiss = { dismissals++ })
        expandSheet()
        unfoldConfiguration()
        assertLastLineReachable()
        assertSelectionKeepsSheet { dismissals }

        // Replay at offset zero: an unconsumed downward drag could otherwise move the sheet.
        scrollBy(-configurationScrollRange().value())
        assertEquals(
            message("selection must also be exercised at scroll offset zero"),
            0f, configurationScrollRange().value(), 1f,
        )
        assertSelectionKeepsSheet { dismissals }
        assertActionsReachable()
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test
    fun `a single long wrapping line also offers expansion`() {
        val longLine = List(12) { XATRIX_LINES.first() }.joinToString(" / ")
        showSheet(ProfileUiState.Configuration.Loaded(listOf(longLine)))
        assertActionsVisible()
        expandSheet()

        unfoldConfiguration()

        compose.onNodeWithText(longLine).assertExists(message("the complete wrapping line must remain in semantics"))
        assertTextAbsent("Tout afficher")
        assertLastLineReachable()
        assertActionsReachable()
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test
    fun `configuration failure retries independently and keeps profile actions available`() {
        val intents = mutableListOf<ProfileIntent>()
        showSheet(ProfileUiState.Configuration.Error, intents::add)

        assertVisible(compose.onNodeWithText("Configuration indisponible").performScrollTo(), "configuration error")
        val retry = compose.onNodeWithContentDescription("Réessayer la configuration matérielle").performScrollTo()
        assertVisible(retry, "configuration retry")
        assertEnabled(retry, "configuration retry")
        retry.performClick()
        assertEquals(
            message("retry must only request the configuration"), listOf(ProfileIntent.RetryConfiguration), intents,
        )
        assertActionsReachable()
        compose.onNodeWithText("Masquer cet utilisateur").performClick()
        assertEquals(message("the block action must remain available"), ProfileIntent.ToggleBlocked, intents.last())
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test
    fun `loading configuration keeps profile information and actions visible through completion`() {
        val state = mutableStateOf(loadedState(ProfileUiState.Configuration.Loading))
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ProfilePreviewSheetContent(state.value, {}, {}, {})
            }
        }

        assertTextAbsent(TITLE)
        assertTextAbsent("Chargement…")
        assertVisible(compose.onNodeWithText("Katowice (PL)"), "profile location while loading")
        assertActionsVisible()

        compose.runOnIdle { state.value = loadedState(ProfileUiState.Configuration.Loaded(XATRIX_LINES)) }

        assertTextAbsent("Chargement…")
        assertVisible(compose.onNodeWithText("Katowice (PL)"), "profile location after loading")
        assertActionsVisible()
        // A nonempty response may change M3's anchors. Assert content order, not anchor offsets.
        assertConfigurationAfterActions()
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test
    fun `full profile shows all configuration lines without expansion`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ProfileScreen(loadedState(ProfileUiState.Configuration.Loaded(XATRIX_LINES)), {}, {}, {})
            }
        }

        assertVisible(compose.onNodeWithText(TITLE).performScrollTo(), "full profile configuration heading")
        assertTrue(message("the full profile must lay out every hardware line"), configurationLayout().lineCount >= 12)
        assertFalse(
            message("the full profile must not ellipsize configuration"), configurationLayout().hasVisualOverflow,
        )
        assertTextAbsent("Tout afficher")
        assertLastLineReachable()
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test
    fun `full profile hides an empty configuration`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ProfileScreen(loadedState(ProfileUiState.Configuration.Loaded(emptyList())), {}, {}, {})
            }
        }

        assertTextAbsent(TITLE)
    }

    private fun showSheet(
        configuration: ProfileUiState.Configuration,
        onIntent: (ProfileIntent) -> Unit = {},
        fontScale: Float = 1f,
        onDismiss: () -> Unit = {},
    ) {
        if (configuration is ProfileUiState.Configuration.Loaded) configurationLines = configuration.lines
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ProfilePreviewSheetContent(loadedState(configuration), onIntent, onDismiss, {})
            }
        }
    }

    private fun expandSheet() {
        val expandable = SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)
        // Robolectric can open directly at the expanded anchor. There is then nothing to expand.
        // Anchor offsets and the presence of a partial anchor are device checks, not JVM contracts.
        if (compose.onAllNodes(expandable).fetchSemanticsNodes().isNotEmpty()) {
            var accepted = false
            compose.onNode(expandable).performSemanticsAction(SemanticsActions.Expand) { accepted = it() }
            compose.waitForIdle()
            assertTrue(message("the sheet must accept its available expand action"), accepted)
        }
        assertTrue(message("the opened sheet must have nonzero height"), sheetBounds().height > 0f)
    }

    private fun unfoldConfiguration() {
        val showAll = compose.onNodeWithText("Tout afficher").performScrollTo()
        assertVisible(showAll, "show all configuration")
        assertEnabled(showAll, "show all configuration")
        showAll.performClick()
        compose.waitForIdle()
    }

    private fun loadedState(configuration: ProfileUiState.Configuration): ProfileUiState =
        ProfileUiState.initial(profile.userId, profile.pseudo, null).copy(
            mode = ProfileUiState.Mode.Loaded(profile),
            configuration = configuration,
        )

    private fun assertSelectionKeepsSheet(dismissals: () -> Int) {
        val text = compose.onNodeWithText(configurationLines.joinToString("\n"))
        val textBefore = text.fetchSemanticsNode()
        val textPositionBefore = textBefore.positionInRoot
        val layout = configurationLayout()
        val viewport = viewportBounds()
        val sheet = compose.onNode(isDialog())
        val sheetOrigin = sheet.fetchSemanticsNode().positionInRoot
        // Pick two real glyphs in the middle of the visible text, away from auto-scroll edges.
        val textPadding = textBefore.size.height - layout.size.height
        val textTop = textPositionBefore.y + textPadding
        val visibleText = fullBounds(textBefore).intersect(viewport)
        val line = layout.getLineForVerticalPosition(visibleText.center.y - textTop)
            .coerceIn(0, layout.lineCount - 3)
        // Inject through the dialog: the text node's touch bounds are clipped by the viewport.
        val textOrigin = Offset(textPositionBefore.x, textTop) - sheetOrigin
        val start = layout.getBoundingBox(layout.getLineStart(line) + 2).center + textOrigin
        // Two lines down: one line (~48 px at xxhdpi) only equals the touch slop.
        val end = layout.getBoundingBox(layout.getLineStart(line + 2) + 2).center + textOrigin
        assertTrue(
            message("selection start=$start must be in viewport=$viewport"), viewport.contains(start + sheetOrigin),
        )
        assertTrue(message("selection end=$end must be in viewport=$viewport"), viewport.contains(end + sheetOrigin))
        val viewConfiguration = textBefore.layoutInfo.viewConfiguration
        assertTrue(
            message("selection drag start=$start end=$end must exceed slop=${viewConfiguration.touchSlop}"),
            (end - start).getDistance() > viewConfiguration.touchSlop,
        )
        val scrollBefore = configurationScrollRange().value()
        val actionsBefore = actionBounds()
        val sheetBefore = sheetBounds()
        val before = measurements()

        sheet.performTouchInput {
            down(start)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(end, delayMillis = 200)
        }

        // First assert with the pointer held: a sheet drag must not settle back unnoticed.
        listOf("pointer held", "pointer released").forEach { phase ->
            if (phase == "pointer released") sheet.performTouchInput { up() }
            val context = message("selection $phase; before=$before")
            assertEquals("no dismissal; $context", 0, dismissals())
            assertEquals("no content scroll; $context", scrollBefore, configurationScrollRange().value(), 1f)
            assertEquals(
                "no text displacement; $context", textPositionBefore.y, text.fetchSemanticsNode().positionInRoot.y, 1f,
            )
            assertEquals("no action displacement; $context", actionsBefore, actionBounds())
            assertEquals("no sheet displacement; $context", sheetBefore, sheetBounds())
        }
    }

    private fun configurationLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val readLayout = requireNotNull(
            compose.onNodeWithText(configurationLines.joinToString("\n"))
                .fetchSemanticsNode(message("configuration Text must exist"))
                .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action,
        ) { message("configuration Text must expose its layout") }
        assertTrue(message("configuration Text must return its layout"), readLayout(layouts))
        assertEquals(message("configuration Text must have one layout"), 1, layouts.size)
        return layouts.single()
    }

    private fun assertTextAbsent(text: String) {
        assertTrue(
            message("text '$text' must be absent"),
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun assertConfigurationAfterActions() {
        val heading = compose.onNodeWithText(TITLE).fetchSemanticsNode(message("configuration heading must exist"))
        actionBounds().forEachIndexed { index, bounds ->
            assertTrue(
                message("configuration heading=${fullBounds(heading)} must follow ${ACTION_LABELS[index]}=$bounds"),
                bounds.bottom <= heading.positionInRoot.y,
            )
        }
    }

    private fun assertActionsVisible() {
        ACTION_LABELS.forEach { assertActionVisible(it) }
    }

    private fun assertActionsReachable() {
        ACTION_LABELS.forEach { label ->
            compose.onNodeWithText(label).performScrollTo()
            assertActionVisible(label)
        }
    }

    private fun assertActionVisible(label: String) {
        val action = compose.onNodeWithText(label)
        assertVisible(action, label)
        assertEnabled(action, label)
    }

    private fun assertVisible(node: SemanticsNodeInteraction, label: String) {
        assertTrue(message("$label must be displayed"), node.isDisplayed())
        // The merged action node includes the whole button. Its unclipped rectangle must fit in
        // the measured viewport AND window; checking clipped bounds alone would accept a crop.
        val bounds = fullBounds(node.fetchSemanticsNode(message("$label must exist")))
        assertInsideViewport(bounds, label)
    }

    private fun assertEnabled(node: SemanticsNodeInteraction, label: String) {
        node.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled)) {
            message("$label must be enabled")
        }
    }

    private fun assertInsideViewport(bounds: Rect, label: String) {
        val viewport = viewportBounds()
        val context = message("$label bounds=$bounds must fit in viewport=$viewport")
        assertTrue("nonzero width; $context", bounds.width > 0f)
        assertTrue("nonzero height; $context", bounds.height > 0f)
        assertTrue("top; $context", bounds.top >= viewport.top - 1f)
        assertTrue("bottom; $context", bounds.bottom <= viewport.bottom + 1f)
        assertTrue("left; $context", bounds.left >= viewport.left - 1f)
        assertTrue("right; $context", bounds.right <= viewport.right + 1f)
    }

    private fun configurationScrollRange() = compose.onNode(hasScrollAction()).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]

    private fun actionBounds() = ACTION_LABELS.map { label ->
        fullBounds(compose.onNodeWithText(label).fetchSemanticsNode(message("action '$label' must exist")))
    }

    private fun sheetBounds() = fullBounds(
        compose.onNodeWithTag("profile_preview_sheet").fetchSemanticsNode(message("the profile sheet must exist")),
    )

    private fun viewportBounds(): Rect {
        val scroll = compose.onNode(hasScrollAction()).fetchSemanticsNode()
        val window = fullBounds(generateSequence(scroll) { it.parent }.last())
        val dialog = compose.onAllNodes(isDialog()).fetchSemanticsNodes().singleOrNull()?.boundsInRoot ?: window
        // Under Robolectric the sheet node reports its size at top 0, without M3's anchored offset,
        // and boundsInRoot clips descendants against that rect: measure the scroller unclipped.
        return fullBounds(scroll).intersect(window).intersect(dialog)
    }

    private fun fullBounds(node: SemanticsNode) =
        Rect(node.positionInRoot, Size(node.size.width.toFloat(), node.size.height.toFloat()))

    private fun lastLineBounds(node: SemanticsNode, layout: TextLayoutResult): Rect {
        // The Text has measured top padding; line coordinates belong to its inner text layout.
        val textTop = node.positionInRoot.y + node.size.height - layout.size.height
        val lastLine = layout.lineCount - 1
        return Rect(
            left = node.positionInRoot.x + layout.getLineLeft(lastLine),
            top = textTop + layout.getLineTop(lastLine),
            right = node.positionInRoot.x + layout.getLineRight(lastLine),
            bottom = textTop + layout.getLineBottom(lastLine),
        )
    }

    private fun scrollBy(delta: Float) {
        if (delta == 0f) return
        var accepted = false
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) {
            accepted = it(0f, delta)
        }
        // ScrollBy starts an animation. Settle it before reading geometry or issuing another
        // request, otherwise a second scroll can cancel the first before its offset changes.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(message("the scroll container must accept ScrollBy($delta)"), accepted)
    }

    private fun assertLastLineReachable() {
        val layout = configurationLayout()
        assertFalse(message("the expanded configuration must lay out its complete text"), layout.hasVisualOverflow)
        val text = compose.onNodeWithText(configurationLines.joinToString("\n"))
        val before = measurements()
        // A single selectable Text has no separate semantics node for its last line. Scroll by
        // the measured distance to that line, not maxValue, and stop as soon as it is visible.
        // Content that already fits needs no scroll. No M3 anchor or overflow is assumed.
        var attempts = 0
        var line = lastLineBounds(text.fetchSemanticsNode(), layout)
        var viewport = viewportBounds()
        while (!fitsVertically(line, viewport) && attempts < 8) {
            assertTrue(message("the scroll viewport must have positive height; before=$before"), viewport.height > 0f)
            val delta = if (line.bottom > viewport.bottom) line.bottom - viewport.bottom else line.top - viewport.top
            scrollBy(delta)
            attempts++
            val nextLine = lastLineBounds(text.fetchSemanticsNode(), layout)
            if (nextLine == line) break
            line = nextLine
            viewport = viewportBounds()
        }
        assertInsideViewport(
            lastLineBounds(text.fetchSemanticsNode(), layout),
            "last rendered line after $attempts scroll requests; before=$before",
        )
    }

    private fun fitsVertically(bounds: Rect, viewport: Rect) =
        bounds.top >= viewport.top - 1f && bounds.bottom <= viewport.bottom + 1f

    private fun message(expectation: String) = "$expectation; ${measurements()}"

    private fun measurements(): String {
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().map { fullBounds(it) }
        val dialog = compose.onAllNodes(isDialog()).fetchSemanticsNodes().singleOrNull()
        val sheet = compose.onAllNodes(hasTestTag("profile_preview_sheet")).fetchSemanticsNodes().singleOrNull()
        val scroll = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().singleOrNull()
        val range = scroll?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        val actions = ACTION_LABELS.associateWith { label ->
            compose.onAllNodes(hasText(label)).fetchSemanticsNodes().singleOrNull()?.let { fullBounds(it) }
        }
        val text = compose.onAllNodes(hasText(configurationLines.joinToString("\n")))
            .fetchSemanticsNodes().singleOrNull()
        val layouts = mutableListOf<TextLayoutResult>()
        text?.config?.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.singleOrNull()
        val lastLine = if (text != null && layout != null) lastLineBounds(text, layout) else null
        return "windows=$roots heights=${roots.map { it.height }} dialog=${dialog?.boundsInRoot} " +
            "sheet=${sheet?.let { fullBounds(it) }} sheetClipped=${sheet?.boundsInRoot} " +
            "scrollViewport=${scroll?.boundsInRoot} scroll.value=${range?.value?.invoke()} " +
            "scroll.maxValue=${range?.maxValue?.invoke()} actions=$actions " +
            "text=${text?.let { fullBounds(it) }} layout=${layout?.size} lines=${layout?.lineCount} " +
            "fontScale=${layout?.layoutInput?.density?.fontScale} lastLine=$lastLine"
    }

    private companion object {
        const val TITLE = "Configuration matérielle"
        val ACTION_LABELS = listOf("Voir le profil complet", "Masquer cet utilisateur")

        // Parsed lines from the real configuration_xatrix_anonymous.html capture (2026-10-30).
        val XATRIX_LINES = listOf(
            "#PC1# P8Z77-V / i7 3770K / 32GB / GTX670 2GB / mint17.1",
            "#PC2# GA-P35C-DS3R / C2D E8500 / 4GB / 4870 1GB",
            "#LAPTOP# LENOVO IDEAPAD Z570 / i7-2670QM / 6GB / GT540M",
            "#PHONE# Galaxy Nexus / A9 1.2GHZ / 16GB / Free@4€",
            "#SERVER@HOME# GA-EP35C-DS3R / C2Q Q6600 / 4GB / 2x250GB raid1 / XenServer6.2",
            "#SERVER@DC# P8H77-M / XEON E3-1225V2 / 16GB / 2x1TB raid1 / XenServer6.2 / eth0/wan 100mbps fr",
            "#PV-SERVER@DC# vCPU 1GHZ / 1GB / 10GB / debian7 / eth0/wan 100mbps ca",
            "#MODEM@BRIDGE# Technicolor TG788vn",
            "#ROUTER@HOME# Ubiquiti EdgeRouter Lite",
            "#AP@HOME# Ubiquiti UniFi AP",
            "#SWITCH@HOME# Netgear GS108Ev2",
            "#FAI@ADSL# OVH / 15Mbps -_-",
        )

        // Stress the layout with repeated captured references; overflow is measured, not assumed.
        val SCROLL_LINES = XATRIX_LINES + XATRIX_LINES + XATRIX_LINES
    }
}

/** Robolectric has no real Surface for the platform text-selection magnifier popup. */
@Implements(Magnifier::class)
class NoopProfileShadowMagnifier {
    @Implementation
    @Suppress("UnusedParameter")
    fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit

    @Implementation
    @Suppress("UnusedParameter")
    fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit

    @Implementation
    fun update() = Unit

    @Implementation
    fun dismiss() = Unit
}
