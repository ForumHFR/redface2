package fr.forumhfr.redface2.feature.profile

import android.content.ClipboardManager
import android.content.Context
import android.widget.Magnifier
import androidx.activity.ComponentDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
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
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
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
import org.robolectric.shadows.ShadowDialog

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
        hasHardwareConfiguration = true,
    )

    private var configurationLines = XATRIX_LINES
    private var nextConfiguration: ProfileUiState.ConfigurationSheet =
        ProfileUiState.ConfigurationSheet.Loaded(XATRIX_LINES)
    private val state = mutableStateOf(loadedState())
    private val intents = mutableListOf<ProfileIntent>()

    @Before
    fun resetFontScale() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun `card hides the link when HFR does not offer it`() {
        val withoutLink = profile.copy(hasHardwareConfiguration = false)
        showProfile(loadedState().copy(mode = ProfileUiState.Mode.Loaded(withoutLink)))

        assertTextAbsent(TITLE)
        assertActionsVisible()
        assertEquals(emptyList<ProfileIntent>(), intents)
    }

    @Test
    fun `card hides the link while the profile loads and when it fails`() {
        showProfile(ProfileUiState.initial(profile.userId, profile.pseudo, null))
        assertTextAbsent(TITLE)
        assertActionsVisible()

        compose.runOnIdle {
            state.value = state.value.copy(mode = ProfileUiState.Mode.Error(HfrErrorKind.Network))
        }

        assertTextAbsent(TITLE)
        assertActionsReachable()
        assertEquals(emptyList<ProfileIntent>(), intents)
    }

    @Test
    fun `one button link follows visible card actions at 360 by 760 dp`() {
        showProfile()
        assertActionsVisible()
        val link = compose.onNodeWithText(TITLE)
        assertVisible(link, "configuration link")
        link.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertEquals(1, compose.onAllNodes(hasText(TITLE)).fetchSemanticsNodes().size)
        val bounds = fullBounds(link.fetchSemanticsNode())
        val density = link.fetchSemanticsNode().layoutInfo.density.density
        assertTrue(message("configuration link must be at least 48 dp high"), bounds.height >= 48f * density)
        actionBounds().forEach { action ->
            assertTrue(message("configuration link must follow the actions"), action.bottom <= bounds.top)
        }
        assertTextAbsent(configurationLines.joinToString("\n"))
        assertEquals(emptyList<ProfileIntent>(), intents)
    }

    @Test
    fun `touching the card link opens a separate sheet with twelve complete lines`() {
        showProfile()
        val card = ShadowDialog.getLatestDialog()

        openConfiguration()

        val configuration = ShadowDialog.getLatestDialog()
        assertTrue("the configuration must have its own window", card.window !== configuration.window)
        assertTrue("the profile dialog must stay open", card.isShowing)
        assertHeadingAndPseudo()
        assertTrue(message("all twelve lines must be laid out"), configurationLayout().lineCount >= 12)
        assertFalse(message("configuration must never ellipsize"), configurationLayout().hasVisualOverflow)
        assertTextAbsent("Tout afficher")
        assertLastLineReachable()
        assertEquals(listOf(ProfileIntent.RequestConfiguration), intents)
    }

    @Test
    fun `configuration starts with a discrete loading indicator only after the touch`() {
        nextConfiguration = ProfileUiState.ConfigurationSheet.Loading
        showProfile()
        compose.onNodeWithContentDescription(LOADING).assertDoesNotExist()

        openConfiguration()

        compose.onNodeWithContentDescription(LOADING).assertExists()
        assertTextAbsent(XATRIX_LINES.joinToString("\n"))
        compose.runOnIdle {
            state.value = state.value.copy(configurationSheet = ProfileUiState.ConfigurationSheet.Loaded(XATRIX_LINES))
        }
        compose.onNodeWithContentDescription(LOADING).assertDoesNotExist()
        assertLastLineReachable()
    }

    @Test
    fun `an empty configuration has an explicit empty message`() {
        nextConfiguration = ProfileUiState.ConfigurationSheet.Loaded(emptyList())
        showProfile()

        openConfiguration()

        assertHeadingAndPseudo()
        assertVisible(compose.onNodeWithText("Aucune configuration renseignée"), "empty configuration")
        dismissConfigurationWithBack()
        assertActionsVisible()
        compose.onNodeWithTag("profile_configuration_sheet").assertDoesNotExist()
    }

    @Test
    fun `configuration failure offers an explicit retry that replaces only the sheet content`() {
        nextConfiguration = ProfileUiState.ConfigurationSheet.Error
        showProfile()
        openConfiguration()
        assertVisible(compose.onNodeWithText("Configuration indisponible"), "configuration failure")
        val retry = compose.onNodeWithContentDescription("Réessayer la configuration matérielle")
        assertVisible(retry, "configuration retry")
        assertEnabled(retry, "configuration retry")
        nextConfiguration = ProfileUiState.ConfigurationSheet.Loaded(XATRIX_LINES)

        retry.performClick()

        assertTextAbsent("Configuration indisponible")
        assertEquals(listOf(ProfileIntent.RequestConfiguration, ProfileIntent.RetryConfiguration), intents)
        assertLastLineReachable()
        dismissConfigurationWithBack()
        assertActionsVisible()
    }

    @Test
    fun `Back closes configuration first and leaves the profile card open`() {
        var cardDismissals = 0
        showProfile(onDismiss = { cardDismissals++ })
        val card = ShadowDialog.getLatestDialog()
        openConfiguration()
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog

        dismissConfigurationWithBack()

        compose.onNodeWithTag("profile_configuration_sheet").assertDoesNotExist()
        compose.onNodeWithTag("profile_preview_sheet").assertExists()
        assertTrue("the profile dialog must remain open", card.isShowing)
        assertFalse("the configuration dialog must close", dialog.isShowing)
        assertEquals(0, cardDismissals)
        assertEquals(listOf(ProfileIntent.RequestConfiguration, ProfileIntent.DismissConfiguration), intents)
        assertActionsVisible()
    }

    @Test
    @Config(qualifiers = "w760dp-h760dp-xxhdpi")
    fun `the configuration scrim dismisses only the upper sheet`() {
        var cardDismissals = 0
        showProfile(onDismiss = { cardDismissals++ })
        val card = ShadowDialog.getLatestDialog()
        openConfiguration()
        val dialog = ShadowDialog.getLatestDialog()
        val scrim = compose.onNode(
            hasClickAction() and hasAnyAncestor(configurationDialogMatcher()) and
                !hasAnyAncestor(hasTestTag("profile_configuration_sheet")),
        )
        val scrimNode = scrim.fetchSemanticsNode()
        val scrimBounds = scrimNode.boundsInRoot
        val surface = sheetBounds()
        // performClick() taps the scrim's centre, which is covered by the sheet. Robolectric also
        // reports the outer sheet at y=0 before M3's anchor offset. A wide window exposes a side
        // gutter: its x coordinates are independent of that offset and of the sheet's height.
        val exposedWidth = surface.left - scrimBounds.left
        assertTrue("no exposed scrim: scrim=$scrimBounds sheet=$surface", exposedWidth > 0f)
        val tap = Offset(scrimBounds.left + exposedWidth / 2f, scrimBounds.center.y)
        assertTrue("scrim tap $tap must be inside scrim=$scrimBounds", scrimBounds.contains(tap))
        assertTrue("scrim tap $tap must be left of sheet=$surface", tap.x < surface.left)

        scrim.performTouchInput { click(tap - scrimNode.positionInRoot) }
        compose.waitForIdle()

        compose.onNodeWithTag("profile_configuration_sheet").assertDoesNotExist()
        compose.onNodeWithTag("profile_preview_sheet").assertExists()
        assertTrue("the profile dialog must remain open", card.isShowing)
        assertFalse("the configuration dialog must close", dialog.isShowing)
        assertEquals(0, cardDismissals)
        assertEquals(listOf(ProfileIntent.RequestConfiguration, ProfileIntent.DismissConfiguration), intents)
        assertActionsVisible()
    }

    @Test
    fun `swiping down from the configuration handle closes only the upper sheet`() {
        var cardDismissals = 0
        showProfile(onDismiss = { cardDismissals++ })
        val card = ShadowDialog.getLatestDialog()
        openConfiguration()
        val dialog = ShadowDialog.getLatestDialog()
        val handle = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and
                hasAnyAncestor(hasTestTag("profile_configuration_sheet")),
        ).fetchSemanticsNode()
        val upperDialog = compose.onNode(configurationDialogMatcher())
        val dialogNode = upperDialog.fetchSemanticsNode()
        val dialogBounds = dialogNode.boundsInRoot
        val start = fullBounds(handle).center
        val end = Offset(start.x, dialogBounds.bottom - 1f)
        val textViewport = viewportBounds()
        assertTrue("handle start=$start must be inside dialog=$dialogBounds", dialogBounds.contains(start))
        assertTrue("handle start=$start must precede selectable text=$textViewport", start.y < textViewport.top)
        assertTrue(
            "swipe must exceed half the sheet height: start=$start end=$end sheet=${sheetBounds()}",
            end.y - start.y > sheetBounds().height / 2f,
        )

        // Inject through the upper window so the gesture can leave the handle's small bounds.
        upperDialog.performTouchInput {
            swipe(start - dialogNode.positionInRoot, end - dialogNode.positionInRoot, durationMillis = 150)
        }
        compose.waitForIdle()

        compose.onNodeWithTag("profile_configuration_sheet").assertDoesNotExist()
        compose.onNodeWithTag("profile_preview_sheet").assertExists()
        assertTrue("the profile dialog must remain open", card.isShowing)
        assertFalse("the configuration dialog must close", dialog.isShowing)
        assertEquals(0, cardDismissals)
        assertEquals(listOf(ProfileIntent.RequestConfiguration, ProfileIntent.DismissConfiguration), intents)
        assertActionsVisible()
    }

    @Test
    fun `double font scale reaches the last hardware line without folding`() {
        showProfile(fontScale = 2f)
        expandCard()
        openConfiguration()
        assertEquals(
            message("configuration Text must use fontScale 2"),
            2f, configurationLayout().layoutInput.density.fontScale, 0f,
        )

        assertLastLineReachable()

        dismissConfigurationWithBack()
        assertActionsReachable()
    }

    @Config(qualifiers = "w760dp-h360dp-land-xxhdpi")
    @Test
    fun `landscape at double font scale keeps configuration and card actions reachable`() {
        showProfile(fontScale = 2f)
        expandCard()
        assertActionsReachable()
        openConfiguration()

        assertLastLineReachable()

        dismissConfigurationWithBack()
        assertActionsReachable()
    }

    @Test
    fun `long press and drag at both scroll edges neither dismiss nor move either sheet`() {
        var cardDismissals = 0
        configurationLines = SCROLL_LINES
        nextConfiguration = ProfileUiState.ConfigurationSheet.Loaded(configurationLines)
        showProfile(onDismiss = { cardDismissals++ })
        openConfiguration()
        assertLastLineReachable()
        assertSelectionKeepsSheet { cardDismissals }

        scrollBy(-configurationScrollRange().value())
        assertEquals(message("selection must also run at offset zero"), 0f, configurationScrollRange().value(), 1f)
        assertSelectionKeepsSheet { cardDismissals }
        assertEquals(listOf(ProfileIntent.RequestConfiguration), intents)
    }

    @Test
    fun `full profile places a link after the signature and opens the shared sheet on touch`() {
        val signedProfile = profile.copy(signatureText = "[:dawa]")
        showProfile(loadedState().copy(mode = ProfileUiState.Mode.Loaded(signedProfile)), fullPage = true)
        val link = compose.onNodeWithText(TITLE).performScrollTo()
        assertVisible(link, "full profile configuration link")
        val signature = compose.onNodeWithText("[:dawa]").fetchSemanticsNode()
        assertTrue(
            message("the configuration link must follow the signature"),
            fullBounds(signature).bottom <= link.fetchSemanticsNode().positionInRoot.y,
        )
        assertTextAbsent(configurationLines.joinToString("\n"))

        link.performClick()

        assertHeadingAndPseudo()
        assertLastLineReachable()
        dismissConfigurationWithBack()
        compose.onNodeWithText("Derniers messages").performScrollTo().assertExists()
    }

    @Test
    fun `full profile hides the link during loading and when HFR has no configuration row`() {
        showProfile(ProfileUiState.initial(profile.userId, profile.pseudo, null), fullPage = true)
        assertTextAbsent(TITLE)

        compose.runOnIdle {
            val withoutLink = profile.copy(hasHardwareConfiguration = false)
            state.value = loadedState().copy(mode = ProfileUiState.Mode.Loaded(withoutLink))
        }

        assertTextAbsent(TITLE)
        compose.onNodeWithText("Derniers messages").performScrollTo().assertExists()
    }

    private fun showProfile(
        initialState: ProfileUiState = loadedState(),
        fullPage: Boolean = false,
        fontScale: Float = 1f,
        onDismiss: () -> Unit = {},
    ) {
        state.value = initialState
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                if (fullPage) {
                    ProfileScreen(state.value, ::onIntent, {}, {})
                } else {
                    ProfilePreviewSheetContent(state.value, ::onIntent, onDismiss, {})
                }
            }
        }
    }

    // State-hoisted UI tests; repository lifetime and real recreation are covered by
    // ProfileViewModelTest and ProfileConfigurationRecreationTest.
    private fun onIntent(intent: ProfileIntent) {
        intents += intent
        when (intent) {
            ProfileIntent.RequestConfiguration, ProfileIntent.RetryConfiguration ->
                state.value = state.value.copy(configurationSheet = nextConfiguration)
            ProfileIntent.DismissConfiguration -> state.value = state.value.copy(configurationSheet = null)
            else -> Unit
        }
    }

    private fun openConfiguration() {
        compose.onNodeWithText(TITLE).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("profile_configuration_sheet").assertExists()
    }

    private fun dismissConfigurationWithBack() {
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        compose.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("profile_configuration_sheet").assertDoesNotExist()
    }

    private fun expandCard() {
        val expandable = SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)
        // Robolectric may open at the expanded anchor already; use the action only when available.
        if (compose.onAllNodes(expandable).fetchSemanticsNodes().isNotEmpty()) {
            var accepted = false
            compose.onNode(expandable).performSemanticsAction(SemanticsActions.Expand) { accepted = it() }
            compose.waitForIdle()
            assertTrue(message("the card must accept its expand action"), accepted)
        }
    }

    private fun assertHeadingAndPseudo() {
        val heading = compose.onNode(
            hasText(TITLE) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        )
        // The heading is pinned above the scroller; it must be displayed in the upper dialog.
        assertTrue(message("configuration heading must be displayed"), heading.isDisplayed())
        compose.onNode(hasText(profile.pseudo) and hasAnyAncestor(configurationDialogMatcher())).assertExists()
    }

    private fun loadedState(): ProfileUiState =
        ProfileUiState.initial(profile.userId, profile.pseudo, null).copy(mode = ProfileUiState.Mode.Loaded(profile))

    private fun configurationDialogMatcher() =
        isDialog() and hasAnyDescendant(hasTestTag("profile_configuration_sheet"))

    private fun assertSelectionKeepsSheet(dismissals: () -> Int) {
        val clipboard = RuntimeEnvironment.getApplication()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.runOnIdle { clipboard.clearPrimaryClip() }
        val text = compose.onNodeWithText(configurationLines.joinToString("\n"))
        val textBefore = text.fetchSemanticsNode()
        val textPositionBefore = textBefore.positionInRoot
        val layout = configurationLayout()
        val viewport = viewportBounds()
        val sheet = compose.onNode(configurationDialogMatcher())
        val sheetOrigin = sheet.fetchSemanticsNode().positionInRoot
        // Pick two real glyphs in the middle of the visible text, away from auto-scroll edges.
        val textPadding = textBefore.size.height - layout.size.height
        val textTop = textPositionBefore.y + textPadding
        val visibleText = fullBounds(textBefore).intersect(viewport)
        val line = layout.getLineForVerticalPosition(visibleText.center.y - textTop)
            .coerceIn(0, layout.lineCount - 3)
        // Inject through the dialog: the text node's touch bounds are clipped by the viewport.
        val textOrigin = Offset(textPositionBefore.x, textTop) - sheetOrigin
        val startOffset = layout.getLineStart(line) + 2
        val start = layout.getBoundingBox(startOffset).center + textOrigin
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
        val cardBefore = fullBounds(compose.onNodeWithTag("profile_preview_sheet").fetchSemanticsNode())
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
            assertEquals(
                "no card displacement; $context", cardBefore,
                fullBounds(compose.onNodeWithTag("profile_preview_sheet").fetchSemanticsNode()),
            )
        }

        // Copy the actual touch selection through SelectionContainer's keyboard handler. Clearing
        // the clipboard above makes an ineffective gesture fail, including at the second scroll edge.
        sheet.performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.C)
            keyUp(Key.C)
            keyUp(Key.CtrlLeft)
        }
        compose.runOnIdle {
            val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            assertTrue(message("the gesture must select and copy text"), copied.isNotBlank())
            assertTrue(
                message("copied text must come from the configuration"),
                configurationLines.joinToString("\n").contains(copied),
            )
            // Visual lines may be wraps within one hardware entry, without a newline character.
            assertTrue(
                message("the drag must extend selection beyond the initial word: '$copied'"),
                copied.length > layout.getWordBoundary(startOffset).length,
            )
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

    private fun configurationScrollRange() = scrollNode().fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]

    private fun actionBounds() = ACTION_LABELS.map { label ->
        fullBounds(compose.onNodeWithText(label).fetchSemanticsNode(message("action '$label' must exist")))
    }

    private fun sheetBounds() = fullBounds(
        compose.onNodeWithTag("profile_configuration_sheet").fetchSemanticsNode(),
    )

    private fun scrollNode(): SemanticsNodeInteraction =
        if (state.value.configurationSheet != null) {
            compose.onNodeWithTag("profile_configuration_scroll")
        } else {
            compose.onNode(hasScrollAction())
        }

    private fun viewportBounds(): Rect {
        val scroll = scrollNode().fetchSemanticsNode()
        val window = fullBounds(generateSequence(scroll) { it.parent }.last())
        val dialog = generateSequence(scroll) { it.parent }.firstOrNull { isDialog().matches(it) }
            ?.let { fullBounds(it) } ?: window
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
        scrollNode().performSemanticsAction(SemanticsActions.ScrollBy) {
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
        val dialogs = compose.onAllNodes(isDialog()).fetchSemanticsNodes().map { fullBounds(it) }
        val tag = if (state.value.configurationSheet != null) "profile_configuration_sheet" else "profile_preview_sheet"
        val sheet = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().singleOrNull()
        val scrollMatcher = if (state.value.configurationSheet != null) {
            hasTestTag("profile_configuration_scroll")
        } else {
            hasScrollAction()
        }
        val scroll = compose.onAllNodes(scrollMatcher).fetchSemanticsNodes().singleOrNull()
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
        return "windows=$roots heights=${roots.map { it.height }} dialogs=$dialogs " +
            "sheet=${sheet?.let { fullBounds(it) }} sheetClipped=${sheet?.boundsInRoot} " +
            "scrollViewport=${scroll?.boundsInRoot} scroll.value=${range?.value?.invoke()} " +
            "scroll.maxValue=${range?.maxValue?.invoke()} actions=$actions " +
            "text=${text?.let { fullBounds(it) }} layout=${layout?.size} lines=${layout?.lineCount} " +
            "fontScale=${layout?.layoutInput?.density?.fontScale} lastLine=$lastLine"
    }

    private companion object {
        const val TITLE = "Configuration matérielle"
        const val LOADING = "Chargement de la configuration matérielle"
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
