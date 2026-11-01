package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionAdapter
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionContract
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** ED-160 à ED-172 — prédictions par test dans le contrat commun, contre e5226a65. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PostEditorSubmissionContractTest(private val variant: String) : EditorSubmissionContract() {
    override fun createAdapter(
        scope: TestScope,
        preferences: Flow<Boolean>,
        delayedSharedRestore: Boolean,
    ): EditorSubmissionAdapter = PostEditorViewModelTest().submissionAdapter(
        variant, scope, preferences, delayedSharedRestore,
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants(): List<Array<String>> = listOf(arrayOf("reply"), arrayOf("quote"), arrayOf("edit"))
    }
}
