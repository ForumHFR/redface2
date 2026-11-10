package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import fr.forumhfr.redface2.core.model.write.PostSubmitOperation
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorHostMemory
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise the real screen collectors after restoration mounts them before auth resolves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorSubmitCallbackRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `restored post editor uses the authenticated callback and preserves the submitted result`() {
        val restoration = StateRestorationTester(compose)
        val account = mutableStateOf<String?>("xaat")
        val effects = Channel<PostEditorEffect>(Channel.BUFFERED)
        val model = mockk<PostEditorViewModel>(relaxUnitFun = true)
        every { model.state } returns MutableStateFlow(
            PostEditorState(PostEditorMode.Reply, 23, 35421, null, 2, 401),
        )
        every { model.effects } returns effects.receiveAsFlow()
        every { model.bodyFieldState } returns TextFieldState()
        every { model.bodyScrollState } returns ScrollState(0)
        every { model.bodyCaretRevealResetKey } returns 0
        every { model.bodyInputTransformation } returns InputTransformation {}
        every { model.chromeEvents } returns PostEditorChromeEvents()
        every { model.chromeMemory } returns EditorHostMemory<PostEditorChromeSurface>()
        val received = mutableListOf<PostEditorEffect.SubmitSucceeded>()
        restoration.setContent {
            val owner = rememberSubmitAccount(account.value)
            val currentAccount = account.value
            val scope = rememberCoroutineScope()
            val picker = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
            every { model.smileyPicker } returns picker
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PostEditorScreen(
                    request = PostEditorRequest(PostEditorMode.Reply, 23, 35421, null, 2, 401),
                    viewModel = model,
                    onSubmitSucceeded = { page, post, quotes, operation ->
                        if (owner != null && owner == currentAccount) {
                            received += PostEditorEffect.SubmitSucceeded(page, post, quotes, operation)
                        }
                    },
                )
            }
        }
        compose.runOnIdle { account.value = null }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(emptyList<PostEditorEffect.SubmitSucceeded>(), received)
        compose.runOnIdle { account.value = "xaat" }
        compose.waitForIdle()
        val success = PostEditorEffect.SubmitSucceeded(2, 640, listOf(640, 655), PostSubmitOperation.Publish)
        compose.runOnIdle { check(effects.trySend(success).isSuccess) }
        compose.waitForIdle()
        assertEquals(listOf(success), received)
    }

    @Test
    fun `restored first-post editor uses the authenticated callback and preserves Edit`() {
        val restoration = StateRestorationTester(compose)
        val account = mutableStateOf<String?>("xaat")
        val effects = Channel<TopicFormEffect>(Channel.BUFFERED)
        val model = mockk<TopicFormViewModel>(relaxUnitFun = true)
        every { model.state } returns MutableStateFlow(
            TopicFormState(TopicFormMode.EditFirstPost, 23, 401, 35421, 1, 640),
        )
        every { model.effects } returns effects.receiveAsFlow()
        every { model.bodyFieldState } returns TextFieldState()
        every { model.bodyScrollState } returns ScrollState(0)
        every { model.bodyInputTransformation } returns InputTransformation {}
        val received = mutableListOf<TopicFormEffect.SubmitSucceeded>()
        restoration.setContent {
            val owner = rememberSubmitAccount(account.value)
            val currentAccount = account.value
            val scope = rememberCoroutineScope()
            val picker = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
            every { model.smileyPicker } returns picker
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicFormScreen(
                    request = TopicFormRequest(TopicFormMode.EditFirstPost, 23, 401, 35421, 1, 640),
                    viewModel = model,
                    onSubmitSucceeded = { page, post, operation ->
                        if (owner != null && owner == currentAccount) {
                            received += TopicFormEffect.SubmitSucceeded(page, post, operation)
                        }
                    },
                    onNewTopicCreated = { _, _, _, _, _ -> error("unexpected topic creation") },
                )
            }
        }
        compose.runOnIdle { account.value = null }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(emptyList<TopicFormEffect.SubmitSucceeded>(), received)
        compose.runOnIdle { account.value = "xaat" }
        compose.waitForIdle()
        val success = TopicFormEffect.SubmitSucceeded(1, 640, PostSubmitOperation.Edit)
        compose.runOnIdle { check(effects.trySend(success).isSuccess) }
        compose.waitForIdle()
        assertEquals(listOf(success), received)
    }
}

// Mirrors :app's rememberSubmitAccount; :feature:editor cannot depend on the navigation host.
@Composable
private fun rememberSubmitAccount(account: String?): String? {
    var owner by rememberSaveable { mutableStateOf(account) }
    if (owner == null && account != null) owner = account
    return owner
}
