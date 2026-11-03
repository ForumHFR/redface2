package fr.forumhfr.redface2.feature.settings

import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.account.RedfaceAccountMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-xxhdpi")
class ReportContentScreenTest {
    @get:Rule
    val compose = createComposeRule()
    private val intents = mutableListOf<ReportContentIntent>()
    private lateinit var hostView: View
    private lateinit var density: Density
    private var imeBottom = 0
    private var systemBarTop = 0
    private var navigationBarBottom = 0

    @Test
    fun loginActionIsVisibleWhenAnonymous() {
        mount(ReportContentUiState(session = ReportSession.ANONYMOUS))
        compose.onNodeWithText("Se connecter").performClick()
        compose.onNodeWithText("Écrire par email")
            .assertTextEquals("Écrire par email", "Ouvre l’application mail")
            .assertHasClickAction()
            .performClick()
        assertEquals(listOf(ReportContentIntent.Login, ReportContentIntent.Email), intents)
    }

    @Test
    fun accountMenuReportActionDispatchesItsActualClick() {
        var reports = 0
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                RedfaceAccountMenu(
                    authState = AuthState.Anonymous, versionName = "0.65.0", versionCode = 123,
                    onLogin = {}, onLogout = {}, onOpenDiagnostics = {}, onReportContent = { reports++ },
                )
            }
        }
        compose.onNodeWithContentDescription("Ouvrir le menu compte").performClick()
        compose.onNodeWithText("Signaler un contenu").performClick()
        assertEquals(1, reports)
    }

    @Test
    fun validFieldsEnableSend() {
        mount(ready())
        compose.onNodeWithText("Envoyer le signalement").performScrollTo().assertIsEnabled()
    }

    @Test
    fun invalidLinkDisablesSend() {
        mount(ready().copy(draft = ReportDraft(ReportReason.OTHER, "Description", "mailto:mail@example.org")))
        compose.onNodeWithText("Envoyer le signalement").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun requiredFieldsGateSend() {
        mount(ready().copy(draft = ReportDraft()))
        compose.onNodeWithText("Envoyer le signalement").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Sécurité des enfants (CSAE/CSAM)").performScrollTo().performClick()
        assertEquals(listOf(ReportContentIntent.ReasonChanged(ReportReason.CHILD_SAFETY)), intents)
    }

    @Test
    fun sendingLocksControls() {
        mount(ready().copy(submission = EditorSubmissionState.PersistingAttempt(
            SubmissionToken(1, EditorSession("session", "tester", "report")),
        )))
        compose.onNodeWithText("Description du problème *").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Lien du contenu (facultatif)").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Autre").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun uncertaintyPreservesInputAndOffersMessageCheck() {
        mount(ready().copy(error = ReportError.UNCERTAIN))
        compose.onNodeWithText("Description privée").performScrollTo().assertExists()
        compose.onNodeWithText("Vérifier mes messages").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(ReportContentIntent.CheckMessages), intents)
    }

    @Test
    fun uncertaintyBecomesFullyVisibleAfterSubmittingFromTheBottom() {
        showSubmissionErrorFromBottom(ReportError.UNCERTAIN)

        assertFullyVisible(
            "Envoi non confirmé. Vérifiez vos messages avant de renvoyer. Votre saisie est conservée.",
        )
        assertFullyVisible("Vérifier mes messages")
        compose.onNodeWithText("Envoyer le signalement").assertIsEnabled()
        compose.onNodeWithText("Vérifier mes messages").performClick()
        assertEquals(ReportContentIntent.CheckMessages, intents.last())
    }

    @Test
    fun networkErrorBecomesFullyVisibleAfterSubmittingFromTheBottom() {
        showSubmissionErrorFromBottom(ReportError.NETWORK)

        assertFullyVisible("Connexion à HFR impossible. Votre saisie est conservée. Réessayez.")
        compose.onNodeWithText("Envoyer le signalement").assertIsEnabled()
    }

    @Test
    fun longDraftKeepsDescriptionHeightBoundedAndSendNearby() {
        val state = mount(ready().copy(draft = ReportDraft(ReportReason.OTHER, multilineDescription(10))))
        val field = compose.onNodeWithText("Description du problème *")
        val initialHeight = field.getUnclippedBoundsInRoot().height
        val longDescription = multilineDescription(200)

        compose.runOnIdle {
            state.value = state.value.copy(draft = state.value.draft.copy(description = longDescription))
        }

        assertEquals(initialHeight, field.getUnclippedBoundsInRoot().height)
        assertEquals(longDescription, state.value.draft.description)
        compose.onNodeWithText("Envoyer le signalement").performScrollTo().assertIsDisplayed()
        val buttonBounds = compose.onNodeWithText("Envoyer le signalement").getUnclippedBoundsInRoot()
        assertTrue(
            "Send must remain near the bounded field",
            buttonBounds.top - field.getUnclippedBoundsInRoot().bottom < 200.dp,
        )
    }

    @Test
    fun expiredSessionOffersLoginAlongsideTheUncertaintyWarning() {
        mount(ready().copy(
            sessionExpired = true, formAvailable = false, error = ReportError.UNCERTAIN,
            submission = EditorSubmissionState.Uncertain(null),
        ))
        compose.onNodeWithText(
            "Envoi non confirmé. Vérifiez vos messages avant de renvoyer. Votre saisie est conservée.",
        ).performScrollTo().assertExists()
        compose.onNodeWithText("Se connecter").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Vérifier mes messages").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Description privée").performScrollTo().assertExists()
        compose.onNodeWithText("Envoyer le signalement").performScrollTo().assertIsNotEnabled()
        assertEquals(listOf(ReportContentIntent.Login, ReportContentIntent.CheckMessages), intents)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-xxhdpi")
    fun landscapeKeyboardKeepsLinkVisibleAndSendReachable() {
        val state = mount(ready())
        focusWithKeyboard("Lien du contenu (facultatif)")
        val link = "https://forum.hardware.fr/hfr/Discussions/sujet_1_1.htm"
        compose.onNodeWithText("Lien du contenu (facultatif)").performTextInput(link)
        assertEquals(link, state.value.draft.link)
        assertFullyVisible("Lien du contenu (facultatif)")
        compose.onNodeWithText("Envoyer le signalement")
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        assertTrue(intents.last() is ReportContentIntent.SubmitWithTimeout)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-xxhdpi")
    fun landscapeKeyboardKeepsDescriptionVisible() {
        assertDescriptionInputAboveKeyboard(fontScale = 1f)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-xxhdpi")
    fun landscapeKeyboardKeepsDescriptionVisibleWithLargeFont() {
        assertDescriptionInputAboveKeyboard(fontScale = 1.3f)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-xxhdpi")
    fun landscapeTallKeyboardKeepsInputFocusedAndSendReachable() {
        val state = mount(ready().copy(draft = ReportDraft(ReportReason.OTHER, "")))
        val label = "Description du problème *"
        focusWithKeyboard(label, keyboardHeight = 220.dp)
        val description = multilineDescription(12)
        compose.onNodeWithText(label).performTextInput(description)
        compose.onNodeWithText(label).assertIsFocused()
        assertEquals(description, state.value.draft.description)
        // At 220 dp the viewport cannot hold a whole field; this only checks reachability.
        compose.onNodeWithText("Envoyer le signalement")
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        assertTrue(intents.last() is ReportContentIntent.SubmitWithTimeout)
    }

    private fun ready() = ReportContentUiState(
        session = ReportSession.AUTHENTICATED, draftReady = true, formAvailable = true,
        draft = ReportDraft(ReportReason.OTHER, "Description privée"),
    )

    private fun mount(state: ReportContentUiState, fontScale: Float = 1f): MutableState<ReportContentUiState> {
        val currentState = mutableStateOf(state)
        compose.setContent {
            val scaledDensity = Density(LocalDensity.current.density, fontScale)
            CompositionLocalProvider(LocalDensity provides scaledDensity) {
                RedfaceTheme(darkTheme = false, dynamicColor = false) {
                    val view = LocalView.current
                    val currentDensity = LocalDensity.current
                    val keyboardBottom = WindowInsets.ime.getBottom(currentDensity)
                    val statusTop = WindowInsets.statusBars.getTop(currentDensity)
                    val navigationBottom = WindowInsets.navigationBars.getBottom(currentDensity)
                    SideEffect {
                        hostView = view
                        density = currentDensity
                        imeBottom = keyboardBottom
                        systemBarTop = statusTop
                        navigationBarBottom = navigationBottom
                    }
                    ReportContent(currentState.value, { intent ->
                        intents += intent
                        currentState.value = editedState(currentState.value, intent)
                    })
                }
            }
        }
        return currentState
    }

    private fun editedState(state: ReportContentUiState, intent: ReportContentIntent) = when (intent) {
        is ReportContentIntent.DescriptionChanged -> state.copy(
            draft = state.draft.copy(description = intent.description),
        )
        is ReportContentIntent.LinkChanged -> state.copy(draft = state.draft.copy(link = intent.link))
        else -> state
    }

    private fun showSubmissionErrorFromBottom(error: ReportError) {
        val state = mount(ready().copy(draft = ReportDraft(ReportReason.OTHER, multilineDescription(200))))
        compose.onNodeWithText("Envoyer le signalement").performScrollTo().performClick()
        assertTrue(intents.single() is ReportContentIntent.SubmitWithTimeout)
        compose.runOnIdle {
            state.value = state.value.copy(submission = EditorSubmissionState.PersistingAttempt(
                SubmissionToken(1, EditorSession("session", "tester", "report")),
            ))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            state.value = state.value.copy(
                error = error,
                submission = if (error == ReportError.UNCERTAIN) {
                    EditorSubmissionState.Uncertain(null)
                } else {
                    EditorSubmissionState.Ready
                },
            )
        }
        compose.waitForIdle()
    }

    private fun assertDescriptionInputAboveKeyboard(fontScale: Float) {
        val state = mount(ready().copy(draft = ReportDraft(ReportReason.OTHER, "")), fontScale)
        val label = "Description du problème *"
        focusWithKeyboard(label)
        val field = compose.onNodeWithText(label)
        repeat(12) { line ->
            field.performTextInput("Ligne $line\n")
            assertFullyVisible(label)
        }
        val description = (0 until 12).joinToString("") { "Ligne $it\n" }
        assertEquals(description, state.value.draft.description)
        val caret = description.length / 2
        field.performTextInputSelection(TextRange(caret))
        field.performTextInput("Ajout au milieu\n")
        assertEquals(
            description.take(caret) + "Ajout au milieu\n" + description.drop(caret),
            state.value.draft.description,
        )
        assertFullyVisible(label)
    }

    private fun assertFullyVisible(label: String): DpRect {
        val node = compose.onNodeWithText(label).assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        // Checking the unclipped field as well prevents a partly visible node from passing.
        assertEquals("$label is clipped by the scroll viewport", bounds, node.getBoundsInRoot())
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val keyboardInset = with(density) { imeBottom.toDp() }
        val topInset = with(density) { systemBarTop.toDp() }
        assertTrue("$label must clear the top system bar", bounds.top >= root.top + topInset)
        assertTrue("$label must stay above the IME", bounds.bottom <= root.bottom - keyboardInset)
        assertTrue("$label must have a visible height", bounds.height > 0.dp)
        return bounds
    }

    private fun focusWithKeyboard(label: String, keyboardHeight: Dp = 160.dp) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        val keyboardHeightPx = with(density) { keyboardHeight.roundToPx() }
        val statusBarHeight = with(density) { 24.dp.roundToPx() }
        val navigationBarHeight = with(density) { 24.dp.roundToPx() }
        // Exercise the real Compose inset listener; Robolectric does not run a keyboard service.
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarHeight, 0, 0))
            .setVisible(WindowInsetsCompat.Type.statusBars(), true)
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBarHeight))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboardHeightPx))
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(hostView, insets) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(keyboardHeightPx, imeBottom)
            assertEquals(statusBarHeight, systemBarTop)
            assertEquals(navigationBarHeight, navigationBarBottom)
        }
        compose.onNodeWithText(label).assertIsFocused().assertIsDisplayed()
    }

    private fun multilineDescription(lines: Int) = (1..lines).joinToString("\n") { "Ligne $it" }
}
