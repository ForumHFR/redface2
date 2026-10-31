package fr.forumhfr.redface2.core.ui.editor

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmileyPickerDetailFeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
    private val perso = EditorSmiley("[:ticento:6]", "https://example.invalid/a.gif", EditorSmileySource.WIKI)
    private val other = perso.copy(token = "[:ipoule2]")
    private val controller = SmileyPickerController(
        CoroutineScope(dispatcher), { _, _ -> emptyList() },
        favoriteSources = SmileyPickerFavoriteSources(
            hfrFavoriteMutations = SmileyFavoriteMutations({ _, _ -> gate.await() }, { _, _ -> gate.await() }),
            localCollections = FakePickerLocalCollections("tester"),
        ),
    )

    @Test
    fun `failure emitted before entry appears in neither the same nor another smiley detail`() {
        startMutation()
        failMutation()
        controller.onDetailChanged(perso)
        mount()
        failure().assertDoesNotExist()
        compose.runOnIdle { controller.onDetailChanged(other) }
        failure().assertDoesNotExist()
    }

    @Test
    fun `failure of an earlier mutation for another smiley stays out of the current detail`() {
        startMutation()
        controller.onDetailChanged(other)
        mount()
        compose.runOnIdle { failMutation() }
        failure().assertDoesNotExist()
        compose.runOnIdle {
            val state = controller.state.value as SmileyPickerState.Open
            assertEquals(SmileyFavoriteFeedback.TransportFailure, state.favoriteFeedback)
            assertEquals(perso.token, state.favoriteFeedbackToken)
        }
    }

    @Test
    fun `failure emitted during the right detail is visible but not replayed on its next visit`() {
        startMutation()
        controller.onDetailChanged(perso)
        mount()
        compose.runOnIdle { failMutation() }
        failure().assertIsDisplayed()
        compose.runOnIdle { controller.onDetailChanged(null) }
        compose.runOnIdle { controller.onDetailChanged(perso) }
        failure().assertDoesNotExist()
    }

    @Test
    fun `completion after opening and before first composition is still eligible`() {
        startMutation()
        controller.onDetailChanged(perso)
        failMutation()
        mount()
        failure().assertIsDisplayed()
    }

    private fun startMutation() {
        dispatcher.scheduler.runCurrent()
        controller.open()
        controller.onFavoriteClicked(perso)
        dispatcher.scheduler.runCurrent()
    }

    private fun failMutation() {
        gate.complete(HfrFavoriteSmileyMutationResult.TransportFailure)
        dispatcher.scheduler.runCurrent()
    }

    private fun mount() {
        compose.setContent {
            val current by controller.state.collectAsState()
            val open = current as SmileyPickerState.Open
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                open.detailSmiley?.let { smiley ->
                    rememberSmileyDetailFeedback(smiley, open)?.let { SmileyFavoriteStatus(it) }
                }
            }
        }
    }

    private fun failure() = compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_network_error))
}
